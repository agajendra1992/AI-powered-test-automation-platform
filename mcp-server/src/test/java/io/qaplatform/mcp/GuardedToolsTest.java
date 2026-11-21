package io.qaplatform.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.security.Role;
import io.qaplatform.mcp.tools.ToolSpec;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The authorisation gate, input validation, error hygiene and audit trail around every tool. */
public class GuardedToolsTest {

    private static final Set<String> REQUIRED_TOOLS = Set.of(
            "run_smoke_tests", "run_regression_tests", "run_ui_tests", "run_api_tests", "run_e2e_tests", "run_tests_by_tag",
            "run_scenario_by_name", "run_testng_suite",
            "list_feature_files", "list_scenarios", "search_feature_files", "get_scenario_details", "list_cucumber_tags",
            "get_step_definitions", "search_step_definitions",
            "get_execution_summary", "get_failed_scenarios", "get_cucumber_report", "get_allure_report", "get_scenario_logs",
            "get_execution_history", "get_screenshot", "get_trace_information",
            "get_browser_status", "capture_page_screenshot", "get_page_information", "inspect_page_elements",
            "find_duplicate_scenarios",
            // AI tools from the specification (all usable without a model)
            "analyze_scenario_failure", "suggest_missing_scenarios", "generate_feature_file", "generate_step_definitions",
            "suggest_regression_tests");

    private static McpPlatform platform(String stdioRole) {
        McpConfig config = TestSupport.config(TestSupport.frameworkDir(), Map.of("stdio.role", stdioRole));
        return new McpPlatform(config, r -> List.of("false"));
    }

    private static McpSchema.CallToolResult call(McpPlatform platform, String tool, Map<String, Object> args) {
        SyncToolSpecification spec = platform.toolSpecifications().stream()
                .filter(s -> s.tool().name().equals(tool)).findFirst().orElseThrow();
        return spec.callHandler().apply(null, new McpSchema.CallToolRequest(tool, args));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @Test
    public void exposesEverySpecifiedToolWithRoleAndReadOnlyAnnotations() {
        McpPlatform platform = platform("viewer");
        Map<String, McpSchema.Tool> tools = platform.toolSpecifications().stream()
                .collect(Collectors.toMap(s -> s.tool().name(), SyncToolSpecification::tool));
        assertThat(tools.keySet()).containsAll(REQUIRED_TOOLS);
        assertThat(tools.get("list_scenarios").annotations().readOnlyHint()).isTrue();
        assertThat(tools.get("run_regression_tests").annotations().readOnlyHint()).isFalse();
        assertThat(tools.get("run_regression_tests").description()).contains("requires role: runner");
        assertThat(tools.get("approve_execution").description()).contains("requires role: admin");
        // Every tool that starts, changes or decides a run, spends the AI budget on generation, or writes files
        java.util.Set<String> stateChanging = java.util.Set.of("cancel_execution", "approve_execution", "reject_execution",
                "execute_test_plan", "generate_feature_file", "generate_step_definitions", "accept_generated_draft");
        for (ToolSpec spec : platform.specs()) {
            if (spec.name().startsWith("run_") || stateChanging.contains(spec.name())) {
                assertThat(spec.requiredRole()).as(spec.name()).isIn(Role.RUNNER, Role.ADMIN);
                assertThat(spec.readOnly()).as(spec.name()).isFalse();
            }
        }
        assertThat(platform.specs().stream().filter(s -> s.name().equals("accept_generated_draft")).findFirst().orElseThrow().requiredRole())
                .isEqualTo(Role.ADMIN);
    }

    @Test
    public void viewerCannotRunTestsAndTheDenialIsAudited() throws Exception {
        McpPlatform platform = platform("viewer");
        McpSchema.CallToolResult result = call(platform, "run_smoke_tests", Map.of());
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("forbidden", "requires role 'runner'");
        assertThat(Files.readString(platform.audit().file())).contains("\"tool\":\"run_smoke_tests\"", "\"outcome\":\"forbidden\"");
    }

    @Test
    public void unknownArgumentsAndInjectionAttemptsAreRejected() {
        McpPlatform platform = platform("runner");
        assertThat(text(call(platform, "run_smoke_tests", Map.of("suite", "x")))).contains("invalid_argument", "Unknown argument");
        assertThat(text(call(platform, "run_tests_by_tag", Map.of("tag_expression", "@api; rm -rf /")))).contains("invalid_argument");
        assertThat(text(call(platform, "run_tests_by_tag", Map.of("tag_expression", "@api -DskipTests")))).contains("invalid_argument");
        assertThat(text(call(platform, "run_tests_by_tag", Map.of("tag_expression", "@does_not_exist")))).contains("No scenario matches");
        assertThat(text(call(platform, "run_smoke_tests", Map.of("environment", "prod")))).contains("must be one of");
        assertThat(text(call(platform, "run_testng_suite", Map.of("suite", "../pom.xml")))).contains("must be one of");
        assertThat(text(call(platform, "get_execution_status", Map.of("execution_id", "../../etc/passwd")))).contains("invalid_argument");
        assertThat(text(call(platform, "capture_page_screenshot", Map.of("url", "http://169.254.169.254/latest/meta-data"))))
                .contains("forbidden", "outside the application under test");
    }

    @Test
    public void discoveryWorksForViewers() {
        McpPlatform platform = platform("viewer");
        McpSchema.CallToolResult result = call(platform, "list_scenarios", Map.of("tag_expression", "@api and @smoke"));
        assertThat(result.isError()).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.structuredContent();
        assertThat(data.get("testCases")).isEqualTo(2);
        assertThat(text(call(platform, "search_step_definitions", Map.of("query", "the response status code should be 201", "mode", "match"))))
                .contains("reuse the existing step");
    }

    @Test
    public void aiToolsDegradeGracefullyWithoutAModelAndForViewers() {
        McpPlatform platform = platform("viewer");
        assertThat(platform.ai().enabled()).as("AI is off unless configured").isFalse();
        String plan = text(call(platform, "plan_test_execution", Map.of("request", "payment regression in qa")));
        assertThat(plan).contains("planId", "Model calls need the runner role");
        assertThat(text(call(platform, "generate_feature_file", Map.of("user_story", "As a user I want x")))).contains("requires role 'runner'");
        assertThat(text(call(platform, "accept_generated_draft", Map.of("draft_id", "draft-20261119-101500-a1b2c3")))).contains("requires role 'admin'");
        String status = text(call(platform, "get_ai_status", Map.of()));
        assertThat(status).contains("\"enabled\" : false", "QA_AI_PROVIDER=none");
    }

    @Test
    public void serverInfoListsWhatTheCallerMayDo() {
        McpPlatform platform = platform("viewer");
        String info = text(call(platform, "get_server_info", Map.of()));
        assertThat(info).contains("\"role\" : \"viewer\"", "deniedTools", "run_smoke_tests");
    }
}
