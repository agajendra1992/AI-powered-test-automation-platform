package io.qaplatform.mcp.config;

import io.qaplatform.mcp.util.PathNames;
import io.qaplatform.mcp.security.Role;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Server settings. Each value is read from a system property ({@code -Dqa.mcp.x.y}) or the
 * matching environment variable ({@code QA_MCP_X_Y}); safe defaults apply otherwise.
 *
 * <p>Defaults are deliberately restrictive: stdio clients are read-only viewers, HTTP binds to
 * loopback, staging needs a second person's approval, and production is never allowed.
 */
public record McpConfig(
        Path projectDir,
        Path stateDir,
        String mavenCommand,
        Set<String> allowedEnvironments,
        Set<String> approvalEnvironments,
        Role stdioRole,
        String stdioUser,
        Duration runTimeout,
        int maxQueuedRuns,
        String transport,
        String httpHost,
        int httpPort,
        Path tokensFile,
        Set<String> browserOrigins,
        Duration browserTimeout) {

    /** Environments the framework knows about. Anything else (e.g. "prod") is rejected outright. */
    public static final Set<String> KNOWN_ENVIRONMENTS = Set.of("local", "qa", "staging");
    public static final Duration MAX_RUN_TIMEOUT = Duration.ofMinutes(120);

    public static McpConfig fromEnvironment() {
        return from(key -> {
            String sys = System.getProperty(key);
            if (sys != null && !sys.isBlank()) {
                return sys.trim();
            }
            String env = System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_'));
            return env == null || env.isBlank() ? null : env.trim();
        });
    }

    /** Builds the configuration from a lookup function (tests pass a map). */
    public static McpConfig from(Function<String, String> lookup) {
        Function<String, String> get = key -> lookup.apply("qa.mcp." + key);
        Path project = Path.of(orDefault(get.apply("project.dir"), ".")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(project.resolve("pom.xml")) || !Files.isDirectory(project.resolve("src/test/resources/features"))) {
            throw new IllegalStateException("qa.mcp.project.dir must point at the framework root (pom.xml + src/test/resources/features): " + project);
        }
        Path state = Path.of(orDefault(get.apply("state.dir"), project.resolve(".qa-mcp").toString())).toAbsolutePath().normalize();

        Set<String> allowed = csv(orDefault(get.apply("allowed.envs"), "local,qa,staging"));
        Set<String> unknown = allowed.stream().filter(e -> !KNOWN_ENVIRONMENTS.contains(e)).collect(Collectors.toSet());
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("Unknown environments in qa.mcp.allowed.envs: " + unknown + " (known: " + KNOWN_ENVIRONMENTS + ")");
        }
        Set<String> approval = csv(orDefault(get.apply("approval.envs"), "staging"));

        long timeoutMinutes = Long.parseLong(orDefault(get.apply("run.timeout.minutes"), "30"));
        Duration timeout = Duration.ofMinutes(Math.max(1, Math.min(timeoutMinutes, MAX_RUN_TIMEOUT.toMinutes())));
        String timeoutSeconds = get.apply("run.timeout.seconds");
        if (timeoutSeconds != null) {
            // Fine-grained override (tests, very short smoke checks); still capped at the maximum.
            timeout = Duration.ofSeconds(Math.max(1, Math.min(Long.parseLong(timeoutSeconds), MAX_RUN_TIMEOUT.toSeconds())));
        }

        String maven = orDefault(get.apply("maven.command"), "mvn");
        String mavenName = PathNames.name(Path.of(maven));
        if (!mavenName.matches("mvn|mvnw|mvn\\.cmd|mvnw\\.cmd")) {
            // The server runs exactly one program: Maven. No other executable can be configured.
            throw new IllegalStateException("qa.mcp.maven.command must be mvn or mvnw, got: " + maven);
        }

        String tokens = get.apply("tokens.file");
        Set<String> origins = new LinkedHashSet<>(frameworkOrigins(project, allowed));
        origins.addAll(csv(orDefault(get.apply("browser.origins"), "")).stream().map(McpConfig::origin).toList());

        return new McpConfig(
                project,
                state,
                maven,
                allowed,
                approval,
                Role.parse(orDefault(get.apply("stdio.role"), "viewer")),
                orDefault(get.apply("stdio.user"), "local:" + System.getProperty("user.name", "unknown")),
                timeout,
                Integer.parseInt(orDefault(get.apply("max.queued.runs"), "5")),
                orDefault(get.apply("transport"), "stdio").toLowerCase(Locale.ROOT),
                orDefault(get.apply("http.host"), "127.0.0.1"),
                Integer.parseInt(orDefault(get.apply("http.port"), "8765")),
                tokens == null ? null : Path.of(tokens).toAbsolutePath().normalize(),
                Set.copyOf(origins),
                Duration.ofSeconds(Long.parseLong(orDefault(get.apply("browser.timeout.seconds"), "20"))));
    }

    public boolean requiresApproval(String environment) {
        return approvalEnvironments.contains(environment);
    }

    /** Normalised origin ("scheme://host:port") of a URL. */
    public static String origin(String url) {
        URI uri = URI.create(url.trim());
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Not an absolute URL: " + url);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** Browser tools may open the application under test only: the base URLs of the allowed environments. */
    private static Set<String> frameworkOrigins(Path project, Set<String> environments) {
        Set<String> origins = new LinkedHashSet<>();
        for (String env : environments) {
            Path file = project.resolve("src/test/resources/config/" + env + ".properties");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + file, e);
            }
            String base = props.getProperty("base.url");
            if (base != null && !base.isBlank()) {
                origins.add(origin(base));
            }
        }
        return origins;
    }

    private static Set<String> csv(String value) {
        return Arrays.stream(value.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Safe view for logs and the server-info tool (no token file contents). */
    public Map<String, Object> describe() {
        return Map.of(
                "projectDir", projectDir.toString(),
                "allowedEnvironments", allowedEnvironments,
                "approvalEnvironments", approvalEnvironments,
                "runTimeoutMinutes", runTimeout.toMinutes(),
                "maxQueuedRuns", maxQueuedRuns,
                "transport", transport,
                "browserOrigins", browserOrigins);
    }
}
