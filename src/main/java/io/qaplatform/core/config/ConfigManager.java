package io.qaplatform.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Single source of configuration.
 * <p>
 * Resolution order (highest wins):
 * <ol>
 *   <li>JVM system property ({@code -Dbase.url=...})</li>
 *   <li>Environment variable, key upper-cased with dots as underscores ({@code BASE_URL})</li>
 *   <li>{@code config/<env>.properties} on the classpath</li>
 *   <li>{@code config/default.properties}</li>
 * </ol>
 * The environment is chosen with {@code -Denv=qa} (default {@code local}).
 * Secrets must come from env vars / CI secrets, never from the properties files.
 */
public final class ConfigManager {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigManager.class);
    private static final ConfigManager INSTANCE = new ConfigManager();

    private final Properties properties = new Properties();
    private final String environment;

    private ConfigManager() {
        this.environment = System.getProperty("env", Optional.ofNullable(System.getenv("ENV")).orElse("local"));
        load("config/default.properties", true);
        load("config/" + environment + ".properties", false);
        LOG.info("Configuration loaded for environment '{}'", environment);
    }

    public static ConfigManager get() {
        return INSTANCE;
    }

    private void load(String resource, boolean required) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                if (required) {
                    throw new IllegalStateException("Missing required config resource: " + resource);
                }
                LOG.warn("No config file '{}' found; relying on defaults and overrides", resource);
                return;
            }
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read config " + resource, e);
        }
    }

    public String environment() {
        return environment;
    }

    public Optional<String> find(String key) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) {
            return Optional.of(sys.trim());
        }
        String env = System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        if (env != null && !env.isBlank()) {
            return Optional.of(env.trim());
        }
        String file = properties.getProperty(key);
        return file == null || file.isBlank() ? Optional.empty() : Optional.of(file.trim());
    }

    public String get(String key) {
        return find(key).orElseThrow(() -> new IllegalStateException("Missing configuration key: " + key));
    }

    public String get(String key, String defaultValue) {
        return find(key).orElse(defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return find(key).map(Integer::parseInt).orElse(defaultValue);
    }

    public boolean getBool(String key, boolean defaultValue) {
        return find(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    /** Snapshot for reports / MCP, with anything that looks secret masked. */
    public Map<String, String> safeSnapshot() {
        Map<String, String> out = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            String value = find(key).orElse("");
            out.put(key, isSecret(key) ? "****" : value);
        }
        out.put("env", environment);
        return out;
    }

    private static boolean isSecret(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("password") || k.contains("secret") || k.contains("token") || k.contains("key");
    }

    // ---- Typed accessors for frequently used settings ----

    public String baseUrl() { return get("base.url"); }
    public String apiBaseUrl() { return get("api.base.url"); }
    public String browser() { return get("browser", "chromium"); }
    public boolean headless() { return getBool("headless", true); }
    public int defaultTimeoutMs() { return getInt("timeout.default.ms", 15_000); }
    public int apiTimeoutMs() { return getInt("api.timeout.ms", 20_000); }
    public int retryCount() { return getInt("retry.count", 0); }
    public TraceMode traceMode() { return TraceMode.from(get("trace.mode", "retain-on-failure")); }
    public boolean recordVideo() { return getBool("video.enabled", false); }
    public boolean aiEnabled() { return getBool("ai.enabled", false); }

    public enum TraceMode {
        OFF, ON, RETAIN_ON_FAILURE, ON_FIRST_RETRY;

        static TraceMode from(String raw) {
            return TraceMode.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }
}
