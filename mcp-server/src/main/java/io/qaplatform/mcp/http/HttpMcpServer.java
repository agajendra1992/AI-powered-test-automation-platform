package io.qaplatform.mcp.http;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.qaplatform.mcp.McpPlatform;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.security.TokenStore;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.util.EnumSet;
import java.util.Map;

/**
 * Streamable HTTP transport on embedded Jetty: {@code POST/GET /mcp} (bearer token required) and
 * an unauthenticated {@code GET /health} for container health checks. Binds to loopback by
 * default; expose it only behind TLS.
 */
public final class HttpMcpServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HttpMcpServer.class);
    private final Server jetty;
    private final McpSyncServer mcp;

    public HttpMcpServer(McpPlatform platform) {
        TokenStore tokens = TokenStore.load(platform.config().tokensFile());
        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                .mcpEndpoint("/mcp")
                .contextExtractor(HttpMcpServer::principalContext)
                .build();
        this.mcp = platform.build(transport);

        jetty = new Server();
        ServerConnector connector = new ServerConnector(jetty);
        connector.setHost(platform.config().httpHost());
        connector.setPort(platform.config().httpPort());
        jetty.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder mcpServlet = new ServletHolder("mcp", transport);
        mcpServlet.setAsyncSupported(true);
        context.addServlet(mcpServlet, "/mcp");
        context.addServlet(new ServletHolder("health", new HealthServlet()), "/health");
        context.addServlet(new ServletHolder("metrics", new MetricsServlet(platform)), "/metrics");
        FilterHolder auth = new FilterHolder(new BearerAuthFilter(tokens, platform.audit(), platform.metrics()));
        auth.setAsyncSupported(true);
        context.addFilter(auth, "/mcp", EnumSet.of(DispatcherType.REQUEST));
        jetty.setHandler(context);
        LOG.info("HTTP transport: {} token(s) loaded", tokens.size());
    }

    private static McpTransportContext principalContext(HttpServletRequest request) {
        Object principal = request.getAttribute(BearerAuthFilter.ATTRIBUTE);
        return principal instanceof Principal p ? McpTransportContext.create(Map.of(Principal.CONTEXT_KEY, p)) : McpTransportContext.EMPTY;
    }

    public void start() throws Exception {
        jetty.start();
        String host = ((ServerConnector) jetty.getConnectors()[0]).getHost();
        if (!InetAddress.getByName(host).isLoopbackAddress()) {
            LOG.warn("MCP HTTP is listening on non-loopback address {}; put it behind TLS and a firewall", host);
        }
        LOG.info("MCP HTTP server on http://{}:{}/mcp", host, port());
    }

    public int port() {
        return ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
    }

    public void join() throws InterruptedException {
        jetty.join();
    }

    @Override
    public void close() throws Exception {
        mcp.closeGracefully();
        jetty.stop();
    }

    /** Prometheus text: counts and timings only. Restrict it to the monitoring network in deployments. */
    private static final class MetricsServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
        private final transient McpPlatform platform;

        MetricsServlet(McpPlatform platform) {
            this.platform = platform;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/plain; version=0.0.4");
            resp.getWriter().write(platform.renderMetrics());
        }
    }

    private static final class HealthServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.getWriter().write("{\"status\":\"UP\",\"server\":\"" + McpPlatform.NAME + "\"}");
        }
    }
}
