package io.qaplatform.core.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.qaplatform.core.config.ConfigManager;
import io.qaplatform.core.exceptions.FrameworkException;
import io.qaplatform.core.reporting.Json;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads static test data from {@code testdata/*.json} (environment-specific files override shared ones)
 * and produces unique dynamic data so parallel tests never collide.
 */
public final class TestDataManager {

    private static final Map<String, JsonNode> CACHE = new ConcurrentHashMap<>();

    private TestDataManager() {
    }

    /** Reads {@code testdata/<env>/<name>.json}, falling back to {@code testdata/<name>.json}. */
    public static JsonNode load(String name) {
        String env = ConfigManager.get().environment();
        return CACHE.computeIfAbsent(env + ":" + name, k -> {
            JsonNode node = read("testdata/" + env + "/" + name + ".json");
            if (node == null) {
                node = read("testdata/" + name + ".json");
            }
            if (node == null) {
                throw new FrameworkException("Test data not found: testdata/" + env + "/" + name + ".json or testdata/"
                        + name + ".json");
            }
            return node;
        });
    }

    public static <T> T load(String name, String pointer, Class<T> type) {
        return Json.convert(load(name).at(pointer), type);
    }

    public static <T> List<T> loadList(String name, String pointer, TypeReference<List<T>> type) {
        return Json.convert(load(name).at(pointer), type);
    }

    /** Short unique token for building collision-free names/emails across threads and CI shards. */
    public static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Random password meeting common complexity rules; generated per scenario, never stored in files. */
    public static String generatedPassword() {
        return "Pw-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "!9a";
    }

    public static String uniqueEmail(String prefix) {
        return prefix + "." + uniqueSuffix() + "@qa.example.com";
    }

    private static JsonNode read(String resource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : Json.readTree(in);
        } catch (JsonProcessingException e) {
            throw new FrameworkException("Invalid JSON in test data file " + resource + ": " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new FrameworkException("Cannot read test data file " + resource, e);
        }
    }
}
