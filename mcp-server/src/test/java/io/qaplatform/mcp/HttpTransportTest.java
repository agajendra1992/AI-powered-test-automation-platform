package io.qaplatform.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.http.HttpMcpServer;
import io.qaplatform.mcp.security.TokenStore;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Streamable HTTP transport end to end with the SDK's own MCP client: bearer-token
 * authentication, role checks per caller, and the two-person approval flow for staging.
 * Runs use a stand-in build so the test needs no browser or application.
 */
public class HttpTransportTest {

    private final String viewerToken = TokenStore.newToken();
    private final String runnerToken = TokenStore.newToken();
    private final String adminToken = TokenStore.newToken();
    private McpPlatform platform;
    private HttpMcpServer server;
    private String endpoint;

    @BeforeClass
    public void start() throws Exception {
        Path tokens = Files.createTempFile("tokens", ".json");
        Files.writeString(tokens, "["
                + entry("viewer-ui", "viewer", viewerToken) + ","
                + entry("ci-bot", "runner", runnerToken) + ","
                + entry("qa-lead", "admin", adminToken) + "]");
        Path project = TestSupport.fakeProject();
        Files.createDirectories(project.resolve("src/test/resources/config"));
        McpConfig config = TestSupport.config(project, Map.of("transport", "http", "http.port", "0", "tokens.file", tokens.toString()));
        String fakeBuild = "mkdir -p target && printf '{\"total\":3,\"passed\":3,\"failed\":0,\"skipped\":0}' > target/execution-summary.json";
        platform = new McpPlatform(config, r -> List.of("bash", "-c", fakeBuild));
        server = new HttpMcpServer(platform);
        server.start();
        endpoint = "http://127.0.0.1:" + server.port();
    }

    @AfterClass(alwaysRun = true)
    public void stop() throws Exception {
        if (server != null) {
            server.close();
        }
        if (platform != null) {
            platform.close();
        }
    }

    private static String entry(String name, String role, String token) {
        return "{\"name\":\"" + name + "\",\"role\":\"" + role + "\",\"sha256\":\"" + TokenStore.sha256Hex(token) + "\"}";
    }

    private McpSyncClient client(String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(endpoint)
                .endpoint("/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                .httpRequestCustomizer((builder, method, uri, body, context) -> {
                    if (token != null) {
                        builder.header("Authorization", "Bearer " + token);
                    }
                })
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        client.initialize();
        return client;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(McpSyncClient client, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, args));
        Map<String, Object> data = (Map<String, Object>) result.structuredContent();
        data.put("_isError", result.isError());
        return data;
    }

    @Test
    public void requestsWithoutAValidTokenGet401() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(endpoint + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).startsWith("Bearer"));
        assertThatThrownBy(() -> client("qamcp_not-a-real-token-but-long-enough")).isInstanceOf(RuntimeException.class);
        assertThat(Files.readString(platform.audit().file())).contains("auth_failure", "invalid_token", "missing_token");
    }

    @Test
    public void healthEndpointIsOpen() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(endpoint + "/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    @Test
    public void viewerCanDiscoverButNotRun() {
        try (McpSyncClient viewer = client(viewerToken)) {
            assertThat(viewer.listTools().tools()).extracting(McpSchema.Tool::name).contains("run_regression_tests", "list_scenarios");
            Map<String, Object> me = call(viewer, "get_server_info", Map.of());
            assertThat(me.get("you")).asString().contains("viewer-ui", "viewer");
            Map<String, Object> run = call(viewer, "run_smoke_tests", Map.of());
            assertThat(run).containsEntry("_isError", true).containsEntry("error", "forbidden");
        }
    }

    @Test
    public void stagingRunNeedsADifferentAdmin() {
        try (McpSyncClient runner = client(runnerToken); McpSyncClient admin = client(adminToken)) {
            Map<String, Object> submitted = call(runner, "run_smoke_tests", Map.of("environment", "staging"));
            assertThat(submitted).containsEntry("status", "AWAITING_APPROVAL");
            String id = (String) submitted.get("executionId");

            assertThat(call(runner, "approve_execution", Map.of("execution_id", id))).containsEntry("error", "forbidden");
            assertThat(call(admin, "list_pending_approvals", Map.of()).get("pending")).asString().contains(id);

            Map<String, Object> approved = call(admin, "approve_execution", Map.of("execution_id", id, "note", "release 4.2 sign-off"));
            assertThat(approved).containsEntry("status", "QUEUED").containsEntry("approvedBy", "qa-lead");

            Map<String, Object> done = call(runner, "get_execution_status", Map.of("execution_id", id, "wait_seconds", 30));
            assertThat(done).containsEntry("status", "PASSED").containsEntry("final", true);
            assertThat(done.get("summary")).asString().contains("passed=3");
        }
    }
}
