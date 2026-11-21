package io.qaplatform.core.listeners;

import io.cucumber.testng.PickleWrapper;
import org.testng.ITestResult;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stable identity of a test execution (Cucumber scenario = feature URI + line) and the
 * registry of scenarios currently being retried, used by {@code on-first-retry} tracing.
 */
public final class ScenarioIds {

    private static final Set<String> RETRYING = ConcurrentHashMap.newKeySet();

    private ScenarioIds() {
    }

    public static String id(ITestResult result) {
        PickleWrapper p = pickle(result);
        if (p != null) {
            return id(p.getPickle().getUri(), p.getPickle().getScenarioLine());
        }
        return result.getTestClass().getRealClass().getName() + "#" + result.getMethod().getMethodName();
    }

    /** Project-relative {@code path/to.feature:line}, identical on every machine and CI shard. */
    public static String id(URI featureUri, int line) {
        String path = featureUri.toString();
        if ("file".equals(featureUri.getScheme())) {
            Path abs = Path.of(featureUri);
            Path cwd = Path.of("").toAbsolutePath();
            path = (abs.startsWith(cwd) ? cwd.relativize(abs) : abs).toString().replace('\\', '/');
        }
        return path + ":" + line;
    }

    /** Short, filesystem-safe name for artifacts derived from an id. */
    public static String fileSafe(String id) {
        String slug = id.replaceAll("^.*/", "").replaceAll("[^a-zA-Z0-9._-]", "_");
        return slug + "-" + Integer.toHexString(id.hashCode());
    }

    public static String name(ITestResult result) {
        PickleWrapper p = pickle(result);
        return p != null ? p.getPickle().getName()
                : result.getTestClass().getRealClass().getSimpleName() + "." + result.getMethod().getMethodName();
    }

    public static List<String> tags(ITestResult result) {
        PickleWrapper p = pickle(result);
        return p != null ? p.getPickle().getTags() : List.of(result.getMethod().getGroups());
    }

    static void markRetrying(String id) {
        RETRYING.add(id);
    }

    /** True when the scenario with this id ({@code uri:line}) already failed once in this run. */
    public static boolean isRetry(String id) {
        return RETRYING.contains(id);
    }

    private static PickleWrapper pickle(ITestResult result) {
        Object[] params = result.getParameters();
        return params.length > 0 && params[0] instanceof PickleWrapper p ? p : null;
    }
}
