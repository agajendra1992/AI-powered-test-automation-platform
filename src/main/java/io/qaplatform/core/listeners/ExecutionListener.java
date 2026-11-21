package io.qaplatform.core.listeners;

import io.qaplatform.core.config.ConfigManager;
import io.qaplatform.core.reporting.ApiMetrics;
import io.qaplatform.core.reporting.Json;
import io.qaplatform.core.reporting.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Observes execution and never influences it. Produces the machine-readable outputs other
 * layers consume: failure records ({@code target/test-failures/*.json}), the execution summary
 * ({@code target/execution-summary.json}) and Allure environment info.
 */
public class ExecutionListener implements ITestListener, ISuiteListener {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionListener.class);
    public static final Path FAILURE_DIR = Path.of("target", "test-failures");
    public static final Path SUMMARY = Path.of("target", "execution-summary.json");
    private static final int STACK_LINES = 40;

    private final AtomicInteger passed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger retriedAttempts = new AtomicInteger();
    private final AtomicLong totalScenarioMs = new AtomicLong();
    private final Set<String> retriedIds = ConcurrentHashMap.newKeySet();
    private final Set<String> flakyIds = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> failuresByType = new ConcurrentHashMap<>();
    private volatile Instant suiteStart;

    @Override
    public void onStart(ISuite suite) {
        suiteStart = Instant.now();
        writeAllureEnvironment();
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        passed.incrementAndGet();
        addDuration(result);
        String id = ScenarioIds.id(result);
        if (retriedIds.contains(id)) {
            flakyIds.add(id);
        }
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        if (result.wasRetried()) {
            retriedAttempts.incrementAndGet();
            retriedIds.add(ScenarioIds.id(result));
            writeFailure(result); // the failed attempt stays visible
        } else {
            skipped.incrementAndGet();
        }
    }

    @Override
    public void onTestFailure(ITestResult result) {
        failed.incrementAndGet();
        addDuration(result);
        writeFailure(result);
    }

    @Override
    public void onFinish(ISuite suite) {
        int executed = passed.get() + failed.get();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("suite", suite.getName());
        summary.put("environment", ConfigManager.get().environment());
        summary.put("browser", ConfigManager.get().browser());
        summary.put("tagFilter", System.getProperty("cucumber.filter.tags", ""));
        summary.put("total", executed + skipped.get());
        summary.put("passed", passed.get());
        summary.put("failed", failed.get());
        summary.put("skipped", skipped.get());
        summary.put("retriedAttempts", retriedAttempts.get());
        summary.put("flaky", flakyIds.size());
        summary.put("flakyScenarios", flakyIds);
        summary.put("durationMs", suiteStart == null ? 0 : Duration.between(suiteStart, Instant.now()).toMillis());
        summary.put("avgScenarioMs", executed == 0 ? 0 : totalScenarioMs.get() / executed);
        summary.put("threads", suite.getXmlSuite().getDataProviderThreadCount());
        summary.put("shardIndex", Integer.getInteger("shard.index", 0));
        summary.put("shardTotal", Integer.getInteger("shard.total", 1));
        summary.put("failuresByType", failuresByType.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
        summary.put("apiTimings", ApiMetrics.snapshot());
        summary.put("buildNumber", env("GITHUB_RUN_NUMBER"));
        summary.put("commit", env("GITHUB_SHA"));
        summary.put("finishedAt", Instant.now().toString());
        write(SUMMARY, Json.write(summary));
        LOG.info("Execution summary: total={} passed={} failed={} skipped={} flaky={} duration={}ms",
                summary.get("total"), passed.get(), failed.get(), skipped.get(), flakyIds.size(), summary.get("durationMs"));
    }

    private void addDuration(ITestResult result) {
        totalScenarioMs.addAndGet(result.getEndMillis() - result.getStartMillis());
    }

    private void writeFailure(ITestResult result) {
        Throwable t = result.getThrowable();
        String errorType = t == null ? "Unknown" : t.getClass().getName();
        failuresByType.computeIfAbsent(errorType, k -> new AtomicInteger()).incrementAndGet();
        Object attempt = result.getAttribute("retryAttempt");
        String id = ScenarioIds.id(result);
        FailureRecord record = new FailureRecord(
                id, ScenarioIds.name(result),
                result.getParameters().length > 0 ? "cucumber" : "testng",
                ScenarioIds.tags(result),
                ConfigManager.get().environment(),
                ConfigManager.get().browser(),
                errorType,
                t == null ? "" : Redactor.text(String.valueOf(t.getMessage())),
                t == null ? "" : Redactor.text(trimStack(t)),
                result.getEndMillis() - result.getStartMillis(),
                attempt instanceof Integer i ? i : 0,
                Instant.now());
        write(FAILURE_DIR.resolve(ScenarioIds.fileSafe(id) + "-" + System.nanoTime() + ".json"), Json.write(record));
    }

    private static String trimStack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString().lines().limit(STACK_LINES).collect(Collectors.joining("\n"));
    }

    private static String env(String name) {
        return Optional.ofNullable(System.getenv(name)).orElse("local");
    }

    private static void writeAllureEnvironment() {
        Path dir = Path.of(System.getProperty("allure.results.directory", "target/allure-results"));
        StringBuilder sb = new StringBuilder();
        ConfigManager.get().safeSnapshot().forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        sb.append("java=").append(System.getProperty("java.version")).append('\n');
        sb.append("os=").append(System.getProperty("os.name")).append('\n');
        write(dir.resolve("environment.properties"), sb.toString());
    }

    private static synchronized void write(Path path, String content) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content);
        } catch (IOException e) {
            LOG.warn("Could not write {}: {}", path, e.getMessage());
        }
    }
}
