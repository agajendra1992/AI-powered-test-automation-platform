package io.qaplatform.ai;

import io.qaplatform.ai.insights.ExecutionHistory;
import io.qaplatform.ai.insights.FailureCorrelator;
import io.qaplatform.ai.insights.FlakyAnalyzer;
import io.qaplatform.ai.insights.GitChanges;
import io.qaplatform.ai.insights.MaintenanceAdvisor;
import io.qaplatform.ai.insights.RunComparator;
import io.qaplatform.ai.llm.AiClient;
import io.qaplatform.ai.llm.DisabledLlmProvider;
import io.qaplatform.ai.llm.PromptManager;
import io.qaplatform.ai.llm.TokenUsageTracker;
import io.qaplatform.ai.recommend.CoverageAdvisor;
import io.qaplatform.mcp.TestSupport;
import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.discovery.FeatureIndex;
import io.qaplatform.mcp.discovery.StepDefinitionIndex;
import io.qaplatform.mcp.execution.ExecutionRecord;
import io.qaplatform.mcp.execution.ExecutionService;
import io.qaplatform.mcp.execution.ExecutionStatus;
import io.qaplatform.mcp.reporting.ReportReader;
import io.qaplatform.mcp.security.AuditLog;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.security.Role;
import io.qaplatform.mcp.tools.ToolException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * History-based intelligence over imported runs written in the framework's real output formats
 * (execution-summary.json, Cucumber JSON, failure records).
 */
public class InsightsTest {

    private static final Principal CI = new Principal("ci-bot", Role.RUNNER, "test");
    private Path project;
    private ExecutionService executions;
    private ExecutionHistory history;
    private final List<String> runIds = new ArrayList<>();

    /** One imported run: scenario → "p" passed, "f" failed, "F" failed then passed on retry. */
    private String run(String label, Map<String, String> statuses, String errorMessage) throws IOException {
        Path dir = project.resolve("ci/" + label);
        Files.createDirectories(dir.resolve("cucumber-reports"));
        Files.createDirectories(dir.resolve("test-failures"));
        StringBuilder elements = new StringBuilder();
        List<String> flaky = new ArrayList<>();
        int failed = 0;
        int i = 0;
        for (Map.Entry<String, String> e : statuses.entrySet()) {
            String name = e.getKey();
            String s = e.getValue();
            String testId = "src/test/resources/features/x.feature:" + (10 + i);
            if (!s.equals("p")) {
                Files.writeString(dir.resolve("test-failures/f" + i + ".json"), "{\"testId\":\"" + testId + "\",\"name\":\"" + name
                        + "\",\"errorType\":\"com.microsoft.playwright.TimeoutError\",\"message\":\"" + errorMessage + "\",\"retryAttempt\":0,"
                        + "\"environment\":\"qa\",\"browser\":\"webkit\",\"timestamp\":\"2026-11-20T10:00:0" + i + "Z\"}");
            }
            if (s.equals("F")) {
                flaky.add("\"" + testId + "\"");
            } else if (s.equals("f")) {
                failed++;
            }
            String result = s.equals("f") ? "failed" : "passed";
            elements.append(i == 0 ? "" : ",").append("{\"type\":\"scenario\",\"name\":\"").append(name).append("\",\"line\":").append(10 + i)
                    .append(",\"steps\":[{\"keyword\":\"Then \",\"name\":\"x\",\"result\":{\"status\":\"").append(result).append("\"}}]}");
            i++;
        }
        Files.writeString(dir.resolve("cucumber-reports/regression.json"), "[{\"name\":\"F\",\"uri\":\"x.feature\",\"elements\":[" + elements + "]}]");
        Files.writeString(dir.resolve("execution-summary.json"), "{\"suite\":\"Regression\",\"environment\":\"qa\",\"browser\":\"webkit\",\"total\":"
                + statuses.size() + ",\"passed\":" + (statuses.size() - failed) + ",\"failed\":" + failed + ",\"skipped\":0,\"flakyScenarios\":" + flaky + "}");
        ExecutionRecord r = executions.importResults(dir, label, CI);
        runIds.add(r.id());
        return r.id();
    }

    @BeforeClass
    public void history() throws IOException {
        project = TestSupport.fakeProject();
        McpConfig config = TestSupport.config(project, Map.of());
        executions = new ExecutionService(config, r -> List.of("true"), new AuditLog(config.stateDir().resolve("a.log")));
        history = new ExecutionHistory(executions, new ReportReader(project, executions));
        String timeout = "Timeout 30000ms exceeded while waiting for navigation";
        // Alternating, retry-flaky, consistently failing, recovered, stable
        run("ci-1", Map.of("Alternating", "p", "Retry", "p", "Broken", "p", "Recovered", "f", "Stable", "p"), timeout);
        run("ci-2", Map.of("Alternating", "f", "Retry", "F", "Broken", "p", "Recovered", "p", "Stable", "p"), timeout);
        run("ci-3", Map.of("Alternating", "p", "Retry", "p", "Broken", "f", "Recovered", "p", "Stable", "p"), timeout);
        run("ci-4", Map.of("Alternating", "f", "Retry", "p", "Broken", "f", "Recovered", "p", "Stable", "p"), timeout);
        // An outage: three scenarios fail the same way in one run
        outage = run("ci-5", Map.of("Alternating", "f", "Retry", "f", "Broken", "f", "Recovered", "p", "Stable", "p"),
                "Application health check failed: HTTP 503");
    }

    private String outage;

    @Test
    public void importedRunsBecomeHistoryWithFrameworkStatus() {
        assertThat(executions.get(runIds.get(0))).hasValueSatisfying(r -> {
            assertThat(r.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(r.request().kind()).isEqualTo("import");
        });
        assertThat(history.runs(10)).hasSize(5);
        assertThatThrownBy(() -> executions.importResults(Path.of("/etc"), "x", CI)).isInstanceOf(ToolException.class);
    }

    @Test
    public void flakyAnalyticsClassifiesEachPattern() {
        Map<String, String> byScenario = new java.util.HashMap<>();
        new FlakyAnalyzer().analyze(history.outcomes(10), 2).forEach(r -> byScenario.put((String) r.get("scenario"), r.get("classification")
                + " " + r.get("history")));
        assertThat(byScenario).containsEntry("Alternating", "FLAKY pfpff")
                .containsEntry("Retry", "FLAKY pFppf")
                .containsEntry("Broken", "CONSISTENTLY_FAILING ppfff")
                .containsEntry("Recovered", "RECOVERED fpppp")
                .containsEntry("Stable", "STABLE ppppp");
    }

    @Test
    public void failuresAreClusteredBySignatureWithSharedCauseHints() {
        List<Map<String, Object>> clusters = new FailureCorrelator().clusters(history.outcomes(10));
        Map<String, Object> top = clusters.get(0);
        assertThat(top.get("probableCategory")).isEqualTo("TIMING_SYNCHRONIZATION");
        assertThat((List<?>) top.get("scenarios")).hasSizeGreaterThanOrEqualTo(4);
        assertThat(top.get("insights")).asString().contains("recurring in", "only seen on webkit");

        Map<String, Object> envCluster = new FailureCorrelator().clusters(history.outcomesOf(outage, java.time.Instant.now(), "qa", "webkit")).get(0);
        assertThat(envCluster.get("probableCategory")).isEqualTo("ENVIRONMENT_FAILURE");
        assertThat(envCluster.get("insights")).asString().contains("3 scenarios failed the same way in one run");
    }

    @Test
    public void selectionIsComparedWithTheFullRegression() {
        var selected = history.outcomesOf(runIds.get(3), null, "qa", "webkit").stream()
                .filter(o -> !o.scenario().equals("Broken")).toList();
        var full = history.outcomesOf(runIds.get(3), null, "qa", "webkit");
        Map<String, Object> cmp = new RunComparator().compare("selected", selected, "full", full);
        assertThat(cmp.get("escapedSelection")).asString().contains("Broken");
        assertThat(cmp.get("caughtBySelection")).asString().contains("Alternating");
        assertThat(cmp.get("verdict")).asString().contains("found only by the full regression");
    }

    @Test
    public void gitChangesAreReadWithAFixedCommand() throws Exception {
        Path repo = Files.createTempDirectory("git-repo");
        git(repo, "init", "-q");
        git(repo, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "base");
        Files.createDirectories(repo.resolve("src/main/java"));
        Files.writeString(repo.resolve("src/main/java/PaymentPage.java"), "class PaymentPage {}");
        assertThat(GitChanges.since(repo, "HEAD")).containsExactly("src/main/java/PaymentPage.java");
        assertThatThrownBy(() -> GitChanges.since(repo, "--output=/tmp/x")).isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> GitChanges.since(repo, "HEAD;rm")).isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> GitChanges.since(Files.createTempDirectory("nogit"), "HEAD")).hasMessageContaining("not a Git repository");
    }

    @Test
    public void maintenanceAndRequirementCoverageOnTheRealProject() throws Exception {
        Path real = TestSupport.frameworkDir();
        AiClient off = new AiClient(new DisabledLlmProvider("off"), new PromptManager(),
                new TokenUsageTracker(Files.createTempDirectory("u").resolve("u.jsonl"), 1000), 100);
        FeatureIndex features = new FeatureIndex(real);
        Map<String, Object> advice = new MaintenanceAdvisor(real, features, new StepDefinitionIndex(real), history, off).advise(false);
        assertThat(advice.get("countsByType")).asString().contains("duplicate-scenario", "flaky", "consistently-failing");
        assertThat(advice.get("findings")).asString().contains("Supported authentication schemes are accepted");

        McpConfig config = TestSupport.config(real, Map.of());
        List<Map<String, Object>> coverage = new CoverageAdvisor(config, features, off).suggest("payment",
                "1. A declined card is rejected with 402\n2. A payment can be refunded partially\n3. Refunds are only allowed for approved payments in the original currency",
                null, false, false).get("requirementCoverage") instanceof List<?> l ? castRows(l) : List.of();
        assertThat(coverage).hasSize(3);
        assertThat(coverage.get(0)).containsEntry("status", "LIKELY COVERED");
        assertThat(coverage.get(0).get("scenario")).asString().contains("declined");
        assertThat(coverage.get(1)).containsEntry("status", "LIKELY COVERED");
        assertThat(coverage.get(2)).containsEntry("status", "NO MATCHING SCENARIO");
        List<Map<String, Object>> login = castRows((List<?>) new CoverageAdvisor(config, features, off).suggest("login",
                "1. The user can sign out\n2. A wrong password keeps the user on the sign-in page", null, false, false).get("requirementCoverage"));
        assertThat(login.get(0).get("scenario")).asString().contains("Administrator signs out");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castRows(List<?> l) {
        return (List<Map<String, Object>>) l;
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(new String(p.getInputStream().readAllBytes())).isZero();
    }
}
