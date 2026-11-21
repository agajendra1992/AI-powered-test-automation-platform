package io.qaplatform.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full path, the way an AI assistant uses it: an MCP client launches the packaged server over
 * stdio, discovers scenarios and runs the real Java framework (API suite) against the demo app.
 *
 * <p>Not part of {@code mvn test} (the class name ends in IT). Needs the shaded jar and the demo
 * application on :8000:
 * <pre>mvn -f mcp-server/pom.xml package -DskipTests
 * mvn -f mcp-server/pom.xml test -Dtest=StdioFrameworkIT</pre>
 */
@Test
public class StdioFrameworkIT {

    private McpSyncClient client;

    @BeforeClass
    public void connect() throws Exception {
        Path jar = Path.of(System.getProperty("qa.mcp.server.jar"));
        assertThat(jar).as("build the jar first: mvn package -DskipTests").exists();
        ServerParameters params = ServerParameters.builder("java")
                .args("-jar", jar.toString())
                .addEnvVar("QA_MCP_PROJECT_DIR", TestSupport.frameworkDir().toString())
                .addEnvVar("QA_MCP_STATE_DIR", Files.createTempDirectory("qa-mcp-it").toString())
                .addEnvVar("QA_MCP_STDIO_ROLE", "runner")
                .addEnvVar("PATH", System.getenv("PATH"))
                .addEnvVar("HOME", System.getProperty("user.home"))
                .build();
        StdioClientTransport transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(JsonMapper.builder().build()));
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(90)).build();
        McpSchema.InitializeResult init = client.initialize();
        assertThat(init.serverInfo().name()).isEqualTo("qa-platform");
    }

    @AfterClass(alwaysRun = true)
    public void disconnect() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, args));
        assertThat(result.isError()).as(tool + " -> " + result.content()).isFalse();
        return (Map<String, Object>) result.structuredContent();
    }

    public void discoversScenariosThroughMcp() {
        // 34 MCP tools (Phase 3) + 14 AI-layer tools (Phase 4) + 4 knowledge-base tools (Phase 5) + 5 intelligent-QA tools (Phase 6)
        assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                .hasSize(57)
                .contains("run_api_tests", "list_scenarios", "get_execution_status", "approve_execution", "get_server_info");
        assertThat(call("list_scenarios", Map.of("tag_expression", "@api and not @e2e")).get("testCases")).isEqualTo(20);
        assertThat(call("get_scenario_details", Map.of("name", "Create a new user")).get("undefinedSteps")).isEqualTo(0);
    }

    @Test(dependsOnMethods = "discoversScenariosThroughMcp")
    public void runsTheRealApiSuiteAndReportsFrameworkResults() {
        Map<String, Object> started = call("run_api_tests", Map.of("threads", 4));
        String id = (String) started.get("executionId");
        assertThat(started.get("status")).isIn("QUEUED", "RUNNING");

        Map<String, Object> status = started;
        Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
        while (!Boolean.TRUE.equals(status.get("final")) && Instant.now().isBefore(deadline)) {
            status = call("get_execution_status", Map.of("execution_id", id, "wait_seconds", 60));
        }
        assertThat(status).containsEntry("status", "PASSED");
        assertThat(status.get("summary")).asString().contains("total=20", "passed=20", "failed=0");

        Map<String, Object> summary = call("get_execution_summary", Map.of("execution_id", id));
        assertThat(summary.get("summary")).asString().contains("suite=API");
        Map<String, Object> cucumber = call("get_cucumber_report", Map.of("execution_id", id));
        assertThat(cucumber.get("byStatus")).asString().contains("passed=20");
        assertThat(call("get_execution_history", Map.of()).get("executions")).asString().contains(id);
    }
}
