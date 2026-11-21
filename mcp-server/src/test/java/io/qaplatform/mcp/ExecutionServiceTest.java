package io.qaplatform.mcp;

import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.execution.ExecutionRecord;
import io.qaplatform.mcp.execution.ExecutionRequest;
import io.qaplatform.mcp.execution.ExecutionService;
import io.qaplatform.mcp.execution.ExecutionStatus;
import io.qaplatform.mcp.security.AuditLog;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.security.Role;
import io.qaplatform.mcp.tools.ToolException;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle of runs with a stand-in build: a bash script that writes the same outputs the
 * framework writes (target/execution-summary.json) and exits with a chosen code.
 */
public class ExecutionServiceTest {

    private static final Principal ALICE = new Principal("alice", Role.RUNNER, "test");
    private static final Principal BOB_ADMIN = new Principal("bob", Role.ADMIN, "test");
    private static final Principal ALICE_ADMIN = new Principal("alice", Role.ADMIN, "test");
    private static final Principal CAROL = new Principal("carol", Role.RUNNER, "test");

    private record Fixture(McpConfig config, ExecutionService service) {
    }

    /** script: bash body run in the project dir. */
    private static Fixture fixture(String script, Map<String, String> overrides) {
        Path project = TestSupport.fakeProject();
        McpConfig config = TestSupport.config(project, overrides);
        AuditLog audit = new AuditLog(config.stateDir().resolve("audit.log"));
        return new Fixture(config, new ExecutionService(config, r -> List.of("bash", "-c", script), audit));
    }

    private static String summary(int total, int passed, int failed) {
        return "mkdir -p target && printf '{\"suite\":\"Smoke\",\"total\":" + total + ",\"passed\":" + passed + ",\"failed\":" + failed
                + ",\"skipped\":0}' > target/execution-summary.json";
    }

    private static ExecutionRequest request(String env) {
        return new ExecutionRequest("smoke", "testng-smoke.xml", null, null, env, "chromium", 2, 0);
    }

    private static ExecutionRecord runToEnd(Fixture f, ExecutionRecord submitted) {
        return f.service().await(submitted.id(), Duration.ofSeconds(30));
    }

    @Test
    public void passingBuildIsPassedAndResultsAreKept() {
        Fixture f = fixture(summary(4, 4, 0) + "; echo built; exit 0", Map.of());
        ExecutionRecord done = runToEnd(f, f.service().submit(request("local"), ALICE));
        assertThat(done.status()).isEqualTo(ExecutionStatus.PASSED);
        assertThat(done.summary()).containsEntry("passed", 4);
        assertThat(f.service().resultsDir(done.id()).resolve("execution-summary.json")).exists();
        assertThat(f.service().executionDir(done.id()).resolve("console.log")).content().contains("built");
    }

    @Test
    public void failedScenariosMakeTheRunFailedRegardlessOfExitCode() {
        Fixture f = fixture(summary(4, 3, 1) + "; exit 0", Map.of());
        assertThat(runToEnd(f, f.service().submit(request("local"), ALICE)).status()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    public void buildFailureWithoutSummaryIsError() {
        Fixture f = fixture("echo 'COMPILATION ERROR'; exit 1", Map.of());
        ExecutionRecord done = runToEnd(f, f.service().submit(request("local"), ALICE));
        assertThat(done.status()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(done.message()).contains("exit code 1");
    }

    @Test
    public void nonZeroExitWithoutFailuresIsNeverPassed() {
        Fixture f = fixture(summary(4, 4, 0) + "; exit 1", Map.of());
        assertThat(runToEnd(f, f.service().submit(request("local"), ALICE)).status()).isEqualTo(ExecutionStatus.ERROR);
    }

    @Test
    public void emptySelectionIsNoTests() {
        Fixture f = fixture(summary(0, 0, 0) + "; exit 0", Map.of());
        assertThat(runToEnd(f, f.service().submit(request("local"), ALICE)).status()).isEqualTo(ExecutionStatus.NO_TESTS);
    }

    @Test
    public void timeoutStopsTheProcessTree() {
        Fixture f = fixture("sleep 120 & wait", Map.of("run.timeout.seconds", "2"));
        Instant start = Instant.now();
        ExecutionRecord done = runToEnd(f, f.service().submit(request("local"), ALICE));
        assertThat(done.status()).isEqualTo(ExecutionStatus.TIMED_OUT);
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(20));
    }

    @Test
    public void runningExecutionCanBeCancelledByRequesterOnly() {
        Fixture f = fixture("sleep 120 & wait", Map.of());
        ExecutionRecord submitted = f.service().submit(request("local"), ALICE);
        ExecutionRecord running = f.service().await(submitted.id(), Duration.ofMillis(500));
        assertThatThrownBy(() -> f.service().cancel(submitted.id(), CAROL)).isInstanceOf(ToolException.class)
                .hasMessageContaining("Only the requester or an admin");
        f.service().cancel(running.id(), ALICE);
        assertThat(runToEnd(f, submitted).status()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    public void approvalEnvironmentWaitsForADifferentAdmin() {
        Fixture f = fixture(summary(2, 2, 0) + "; exit 0", Map.of());
        ExecutionRecord pending = f.service().submit(request("staging"), ALICE);
        assertThat(pending.status()).isEqualTo(ExecutionStatus.AWAITING_APPROVAL);
        assertThat(f.service().pendingApprovals()).extracting(ExecutionRecord::id).containsExactly(pending.id());

        assertThatThrownBy(() -> f.service().approve(pending.id(), ALICE_ADMIN, "self"))
                .isInstanceOf(ToolException.class).hasMessageContaining("someone other than the requester");

        ExecutionRecord approved = f.service().approve(pending.id(), BOB_ADMIN, "CHG-42");
        assertThat(approved.approvedBy()).isEqualTo("bob");
        ExecutionRecord done = runToEnd(f, approved);
        assertThat(done.status()).isEqualTo(ExecutionStatus.PASSED);
        assertThat(done.decisionNote()).isEqualTo("CHG-42");
    }

    @Test
    public void rejectedRunNeverStarts() throws Exception {
        Fixture f = fixture("touch started.marker; " + summary(1, 1, 0), Map.of());
        ExecutionRecord pending = f.service().submit(request("staging"), ALICE);
        assertThat(f.service().reject(pending.id(), BOB_ADMIN, "change freeze").status()).isEqualTo(ExecutionStatus.REJECTED);
        assertThatThrownBy(() -> f.service().approve(pending.id(), BOB_ADMIN, "later")).isInstanceOf(ToolException.class);
        assertThat(Files.exists(f.config().projectDir().resolve("started.marker"))).isFalse();
    }

    @Test
    public void historySurvivesRestartAndUnfinishedRunsBecomeErrors() {
        Fixture f = fixture(summary(1, 1, 0) + "; exit 0", Map.of());
        ExecutionRecord done = runToEnd(f, f.service().submit(request("local"), ALICE));
        ExecutionRecord pending = f.service().submit(request("staging"), ALICE);
        f.service().close();

        ExecutionService restarted = new ExecutionService(f.config(), r -> List.of("true"),
                new AuditLog(f.config().stateDir().resolve("audit.log")));
        assertThat(restarted.get(done.id())).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(ExecutionStatus.PASSED));
        assertThat(restarted.get(pending.id())).hasValueSatisfying(r -> {
            assertThat(r.status()).isEqualTo(ExecutionStatus.ERROR);
            assertThat(r.message()).contains("restarted");
        });
        restarted.close();
    }

    @Test
    public void queueIsBounded() {
        Fixture f = fixture("sleep 120 & wait", Map.of("max.queued.runs", "1"));
        ExecutionRecord first = f.service().submit(request("local"), ALICE);
        f.service().await(first.id(), Duration.ofMillis(500));
        f.service().submit(request("local"), ALICE);
        assertThatThrownBy(() -> f.service().submit(request("local"), ALICE)).isInstanceOf(ToolException.class)
                .hasMessageContaining("queue is full");
        f.service().close();
    }
}
