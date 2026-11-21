package io.qaplatform.ai;

import io.qaplatform.ai.analysis.FailureAnalyzer;
import io.qaplatform.ai.llm.AiClient;
import io.qaplatform.ai.llm.DisabledLlmProvider;
import io.qaplatform.ai.llm.PromptManager;
import io.qaplatform.ai.llm.TokenUsageTracker;
import io.qaplatform.ai.planning.TestPlanner;
import io.qaplatform.ai.recommend.CoverageAdvisor;
import io.qaplatform.ai.recommend.RegressionAdvisor;
import io.qaplatform.mcp.TestSupport;
import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.discovery.FeatureIndex;
import io.qaplatform.mcp.discovery.StepDefinitionIndex;
import io.qaplatform.mcp.execution.ExecutionRecord;
import io.qaplatform.mcp.execution.ExecutionService;
import io.qaplatform.mcp.reporting.ReportReader;
import io.qaplatform.mcp.security.AuditLog;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.security.Role;
import io.qaplatform.mcp.tools.ToolException;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Natural-language plans, change-based regression selection, coverage gaps and failure analysis. */
public class PlanningAndAdviceTest {

    private static final Principal ALICE = new Principal("alice", Role.RUNNER, "test");
    private static final Path REAL = TestSupport.frameworkDir();

    private record Fixture(McpConfig config, ExecutionService executions, TestPlanner planner, FeatureIndex features, StepDefinitionIndex steps) {
    }

    private static Fixture fixture(AiClient ai, List<String> command) {
        McpConfig config = TestSupport.config(REAL, Map.of());
        ExecutionService executions = new ExecutionService(config, r -> command, new AuditLog(config.stateDir().resolve("a.log")));
        FeatureIndex features = new FeatureIndex(REAL);
        return new Fixture(config, executions, new TestPlanner(config, features, executions, ai), features, new StepDefinitionIndex(REAL));
    }

    private static AiClient ai(io.qaplatform.ai.llm.LlmProvider provider) throws Exception {
        return new AiClient(provider, new PromptManager(), new TokenUsageTracker(Files.createTempDirectory("u").resolve("u.jsonl"), 1_000_000), 1000);
    }

    private static AiClient disabled() throws Exception {
        return ai(new DisabledLlmProvider("off"));
    }

    // ------------------------------------------------------------------ planning

    @Test
    public void keywordRulesTurnTheSpecExampleIntoAPlan() throws Exception {
        Fixture f = fixture(disabled(), List.of("true"));
        Map<String, Object> plan = f.planner().plan(ALICE, "Execute all payment regression scenarios in QA using Chromium.", true);
        assertThat(plan.get("selection")).asString().contains("@payment", "@regression");
        assertThat(plan).containsEntry("environment", "qa").containsEntry("browser", "chromium");
        assertThat(plan.get("testCases")).isEqualTo(10);
        assertThat(plan.get("notIncluded")).asString().contains("27 regression test cases are NOT in this plan", "run_regression_tests");
        assertThat(plan.get("interpretationNote")).asString().contains("AI not configured");
    }

    @Test
    public void rulesHandleFullRegressionNamedScenariosProductionAndVagueness() throws Exception {
        Fixture f = fixture(disabled(), List.of("true"));
        assertThat(f.planner().plan(ALICE, "run the full regression on staging in safari", false))
                .containsEntry("environment", "staging").containsEntry("browser", "webkit").containsEntry("testCases", 37)
                .extractingByKey("approvalRequired").asString().startsWith("yes");
        assertThat(f.planner().plan(ALICE, "please run \"Create a new user\" in firefox", false).get("selection"))
                .asString().contains("Create a new user");
        assertThat(f.planner().plan(ALICE, "run payments on production", false)).containsEntry("status", "NEEDS_CLARIFICATION");
        assertThat(f.planner().plan(ALICE, "do the thing", false)).containsEntry("status", "NEEDS_CLARIFICATION");
        assertThat(f.planner().plan(ALICE, "api smoke", false).get("testCases")).isEqualTo(2);
    }

    @Test
    public void invalidAiInterpretationIsRejectedAndRulesAreUsed() throws Exception {
        ScriptedLlm llm = new ScriptedLlm().answer("""
                {"full_regression": false, "tag_expression": "@payments and @everything", "scenario_names": [],
                 "environment": "qa", "browser": "chromium", "clarification_question": null, "explanation": "x"}""");
        Fixture f = fixture(ai(llm), List.of("true"));
        Map<String, Object> plan = f.planner().plan(ALICE, "payment regression in qa", true);
        assertThat(plan.get("interpretationNote")).asString().contains("rejected", "unknown tag @payments");
        assertThat(plan.get("selection")).asString().contains("@payment");
        assertThat(llm.requests.get(0).messages().get(0).content()).contains("<data source=\"user request\">");
    }

    @Test
    public void validAiInterpretationIsUsedAndExecutedThroughTheService() throws Exception {
        ScriptedLlm llm = new ScriptedLlm().answer("""
                {"full_regression": false, "tag_expression": "@user and @api", "scenario_names": [],
                 "environment": "local", "browser": "chromium", "clarification_question": null, "explanation": "User API checks"}""");
        String fakeBuild = "mkdir -p target && printf '{\"total\":9,\"passed\":9,\"failed\":0,\"skipped\":0}' > target/execution-summary.json";
        Fixture f = fixture(ai(llm), List.of("bash", "-c", fakeBuild));
        Map<String, Object> plan = f.planner().plan(ALICE, "check the user endpoints", true);
        assertThat(plan.get("interpretationNote")).asString().contains("scripted/scripted-model");
        String planId = (String) plan.get("planId");
        Map<String, Object> started = f.planner().execute(ALICE, planId);
        ExecutionRecord done = f.executions().await((String) started.get("executionId"), Duration.ofSeconds(30));
        assertThat(done.request().tagExpression()).isEqualTo("(@user and @api) and not @quarantine");
        assertThat(done.requestedBy()).isEqualTo("alice");
        assertThatThrownBy(() -> f.planner().execute(ALICE, planId)).isInstanceOf(ToolException.class).hasMessageContaining("already executed");
        f.executions().close();
    }

    // ------------------------------------------------------------------ regression advice

    @Test
    public void changedFilesAreTracedToScenarios() throws Exception {
        Fixture f = fixture(disabled(), List.of("true"));
        RegressionAdvisor advisor = new RegressionAdvisor(REAL, f.features(), f.steps(), f.planner(), disabled());

        Map<String, Object> page = advisor.suggest(ALICE, List.of("src/main/java/io/qaplatform/ui/pages/PaymentPage.java"), null, "local", "chromium", false);
        assertThat(page.get("recommendation")).asString().startsWith("TARGETED");
        assertThat(page.get("analysis")).asString().contains("Successfully complete a payment").doesNotContain("Create a new user");

        Map<String, Object> feature = advisor.suggest(ALICE, List.of("src/test/resources/features/api/user_api.feature"), null, "local", "chromium", false);
        assertThat(feature.get("analysis")).asString().contains("Create a new user").doesNotContain("Successfully complete a payment");

        Map<String, Object> core = advisor.suggest(ALICE, List.of("src/main/java/io/qaplatform/core/config/ConfigManager.java", "README.md"),
                null, "local", "chromium", false);
        assertThat(core).containsEntry("recommendation", "FULL REGRESSION");
        assertThat(core.get("filesWithoutTestImpact")).asString().contains("README.md");

        Map<String, Object> docs = advisor.suggest(ALICE, List.of("docs/MCP.md"), null, "local", "chromium", false);
        assertThat(docs.get("recommendation")).asString().startsWith("NO TESTS AFFECTED");
        assertThatThrownBy(() -> advisor.suggest(ALICE, List.of("../../etc/passwd"), null, "local", "chromium", false))
                .isInstanceOf(ToolException.class);
    }

    // ------------------------------------------------------------------ coverage

    @Test
    public void coverageFindsEndpointsWithoutApiAutomation() throws Exception {
        Fixture f = fixture(disabled(), List.of("true"));
        CoverageAdvisor advisor = new CoverageAdvisor(f.config(), f.features(), disabled());
        String spec = """
                {"paths": {"/api/users": {"get": {}, "post": {}}, "/api/users/{uid}": {"delete": {}},
                           "/api/files": {"post": {}}, "/api/reports/users.csv": {"get": {}}}}""";
        Map<String, Object> result = advisor.suggest(null, null, spec, false, false);
        assertThat(result.get("apiEndpointCoverage")).asString()
                .contains("calledByApiAutomation=[GET /api/users, POST /api/users, DELETE /api/users/{uid}]")
                .contains("notCalledByApiAutomation=[POST /api/files, GET /api/reports/users.csv]");
        assertThat(result.get("domainBalance")).asString().contains("@login", "@payment", "@user");
    }

    // ------------------------------------------------------------------ failure analysis

    @Test
    public void failureAnalysisCombinesEvidenceRulesAndAVerifiedAiOpinion() throws Exception {
        Path project = TestSupport.fakeProject();
        Path target = project.resolve("target");
        Files.createDirectories(target.resolve("test-failures"));
        Files.createDirectories(target.resolve("cucumber-reports"));
        Files.writeString(target.resolve("test-failures/a.json"), """
                {"testId":"login.feature:8","name":"Administrator signs in","kind":"cucumber","environment":"local","browser":"chromium",
                 "errorType":"org.opentest4j.AssertionFailedError","message":"Page URL expected to match regex\\nExpected: .*/dashboard$\\nReceived: http://localhost:8000/login",
                 "stackTrace":"org.opentest4j.AssertionFailedError: Page URL expected\\n\\tat LoginSteps.dashboard(LoginSteps.java:40)","retryAttempt":0,
                 "timestamp":"2026-11-19T10:00:00Z"}""");
        Files.writeString(target.resolve("execution-summary.json"), "{\"total\":1,\"passed\":0,\"failed\":1,\"flakyScenarios\":[]}");
        Files.writeString(target.resolve("cucumber-reports/smoke.json"), """
                [{"name":"Sign in","uri":"login.feature","elements":[{"type":"scenario","name":"Administrator signs in","line":8,"tags":[],
                  "steps":[{"keyword":"Then ","name":"the dashboard should welcome \\"QA Admin\\"","result":{"status":"failed","duration":1,
                  "error_message":"Page URL expected"}}]}]}]""");
        McpConfig config = TestSupport.config(project, Map.of());
        ExecutionService executions = new ExecutionService(config, r -> List.of("true"), new AuditLog(config.stateDir().resolve("a.log")));
        ReportReader reports = new ReportReader(project, executions);
        ScriptedLlm llm = new ScriptedLlm().answer("""
                {"category": "AUTHENTICATION_FAILURE", "probable_root_cause": "The sign-in was rejected so the user stayed on /login.",
                 "supporting_evidence": ["Received: http://localhost:8000/login", "Server log said: invalid password"],
                 "confidence": 0.8, "recommended_action": "Check the admin credentials for this environment.", "alternative_categories": []}""");
        Map<String, Object> result = new FailureAnalyzer(reports, executions, ai(llm)).analyze("workspace", "Administrator signs in", true);

        assertThat(result.get("testResult")).asString().startsWith("FAILED");
        assertThat(result.get("ruleBasedClassification")).asString().contains("AUTHENTICATION_FAILURE");
        @SuppressWarnings("unchecked")
        Map<String, Object> opinion = (Map<String, Object>) result.get("aiAssessment");
        assertThat(opinion).containsEntry("status", "UNCONFIRMED HYPOTHESIS").containsEntry("agreesWithRules", true);
        assertThat(opinion.get("supportingEvidenceVerified")).asString().contains("Received: http://localhost:8000/login");
        assertThat(opinion.get("supportingEvidenceNotFound")).asString().contains("invalid password");

        assertThatThrownBy(() -> new FailureAnalyzer(reports, executions, disabled()).analyze("workspace", "A scenario that passed", true))
                .isInstanceOf(ToolException.class).hasMessageContaining("has no failure");
        executions.close();
    }
}
