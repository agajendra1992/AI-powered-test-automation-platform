package io.qaplatform.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.execution.ExecutionService;
import io.qaplatform.mcp.http.HttpMcpServer;
import io.qaplatform.mcp.security.Role;
import io.qaplatform.mcp.security.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CountDownLatch;

/**
 * Entry point.
 * <pre>
 *   java -jar qa-mcp-server.jar                      # stdio (Claude Code, Claude Desktop, IDEs)
 *   QA_MCP_TRANSPORT=http java -jar qa-mcp-server.jar  # streamable HTTP with bearer tokens
 *   java -jar qa-mcp-server.jar new-token ci-bot runner  # create a token + token-file entry
 * </pre>
 * Logs go to stderr only: on stdio, stdout carries the MCP protocol.
 */
public final class McpServerMain {

    private static final Logger LOG = LoggerFactory.getLogger(McpServerMain.class);

    private McpServerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "new-token".equals(args[0])) {
            newToken(args);
            return;
        }
        McpConfig config = McpConfig.fromEnvironment();
        McpPlatform platform = new McpPlatform(config, ExecutionService.maven(config));
        LOG.info("Starting {} {} ({}) for {}", McpPlatform.NAME, McpPlatform.VERSION, config.transport(), config.projectDir());
        switch (config.transport()) {
            case "stdio" -> runStdio(platform);
            case "http" -> runHttp(platform);
            default -> throw new IllegalStateException("qa.mcp.transport must be stdio or http, got " + config.transport());
        }
    }

    private static void runStdio(McpPlatform platform) throws InterruptedException {
        LOG.info("stdio caller '{}' has role '{}' (set QA_MCP_STDIO_ROLE to change)", platform.config().stdioUser(),
                platform.config().stdioRole().label());
        McpSyncServer server = platform.build(new StdioServerTransportProvider(new JacksonMcpJsonMapper(JsonMapper.builder().build())));
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.closeGracefully();
            platform.close();
            stopped.countDown();
        }, "qa-mcp-shutdown"));
        stopped.await();
    }

    private static void runHttp(McpPlatform platform) throws Exception {
        HttpMcpServer server = new HttpMcpServer(platform);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (Exception e) {
                LOG.warn("Error while stopping HTTP server: {}", e.toString());
            }
            platform.close();
        }, "qa-mcp-shutdown"));
        server.start();
        server.join();
    }

    private static void newToken(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: new-token <name> <viewer|runner|admin>");
        }
        String name = args[1];
        if (!name.matches("[A-Za-z0-9._@-]{1,64}")) {
            throw new IllegalArgumentException("Name may contain letters, digits and . _ @ - (max 64)");
        }
        Role role = Role.parse(args[2]);
        String token = TokenStore.newToken();
        // CHECKSTYLE.OFF: CLI output for the operator; this command never starts the server.
        System.out.println("Token (shown once; give it to " + name + " and store it as a secret):");
        System.out.println("  " + token);
        System.out.println("Add this entry to the token file (QA_MCP_TOKENS_FILE):");
        System.out.println("  {\"name\": \"" + name + "\", \"role\": \"" + role.label() + "\", \"sha256\": \"" + TokenStore.sha256Hex(token) + "\"}");
        // CHECKSTYLE.ON
    }
}
