package io.qaplatform.mcp.execution;

import io.qaplatform.mcp.util.PathNames;
import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.security.AuditLog;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.security.Role;
import io.qaplatform.mcp.tools.ToolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs the framework's own Maven + TestNG + Cucumber build, one run at a time, in the background.
 *
 * <ul>
 *   <li>Runs that target an approval environment (default: staging) wait for an admin who is not
 *       the requester.</li>
 *   <li>Each run has a hard timeout; on timeout or cancellation the whole process tree is stopped.</li>
 *   <li>After each run the framework's outputs (summary, failure records, Cucumber/Allure results,
 *       logs, traces) are copied to {@code <state>/executions/<id>/results}, which gives an
 *       execution history that survives the next {@code mvn clean}.</li>
 *   <li>The outcome comes only from Maven's exit code and the framework's summary.</li>
 * </ul>
 * Runs are serialised because every framework run writes to the same {@code target/} directory.
 */
public final class ExecutionService implements AutoCloseable {

    @FunctionalInterface
    public interface CommandFactory {
        List<String> command(ExecutionRequest request);
    }

    public static final Pattern ID = Pattern.compile("run-\\d{8}-\\d{6}-[0-9a-f]{6}");
    private static final Logger LOG = LoggerFactory.getLogger(ExecutionService.class);
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final List<String> RESULT_ITEMS = List.of(
            "execution-summary.json", "test-failures", "cucumber-reports", "allure-results", "logs", "artifacts");

    private final McpConfig config;
    private final CommandFactory commands;
    private final AuditLog audit;
    private final Path executionsDir;
    // Records from older server versions may carry fields that no longer exist: ignore them, keep the history.
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final Map<String, ExecutionRecord> records = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ExecutionRecord>> completions = new ConcurrentHashMap<>();
    private final Map<String, Process> running = new ConcurrentHashMap<>();
    private final Set<String> cancelRequested = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qa-mcp-runner");
        t.setDaemon(true);
        return t;
    });
    private final SecureRandom random = new SecureRandom();

    public ExecutionService(McpConfig config, CommandFactory commands, AuditLog audit) {
        this.config = config;
        this.commands = commands;
        this.audit = audit;
        this.executionsDir = config.stateDir().resolve("executions");
        try {
            Files.createDirectories(executionsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + executionsDir, e);
        }
        loadHistory();
    }

    /** The real command: the configured Maven executable plus the request's validated arguments. */
    public static CommandFactory maven(McpConfig config) {
        return request -> {
            List<String> cmd = new ArrayList<>();
            cmd.add(config.mavenCommand());
            cmd.addAll(request.mavenArguments());
            return cmd;
        };
    }

    // ---------------------------------------------------------------- requests and decisions

    public synchronized ExecutionRecord submit(ExecutionRequest request, Principal requester) {
        boolean needsApproval = config.requiresApproval(request.environment());
        if (!needsApproval) {
            ensureCapacity();
        }
        String id = newId();
        String message = needsApproval
                ? "Waiting for approval: environment '" + request.environment() + "' requires an admin other than "
                  + requester.name() + " to call approve_execution"
                : "Queued";
        ExecutionRecord record = new ExecutionRecord(id, request, needsApproval ? ExecutionStatus.AWAITING_APPROVAL : ExecutionStatus.QUEUED,
                requester.name(), null, null, Instant.now(), null, null, null, message, null);
        completions.put(id, new CompletableFuture<>());
        save(record);
        if (!needsApproval) {
            worker.submit(() -> run(id));
        }
        return record;
    }

    public synchronized ExecutionRecord approve(String id, Principal approver, String note) {
        ExecutionRecord record = require(id);
        if (record.status() != ExecutionStatus.AWAITING_APPROVAL) {
            throw ToolException.conflict("Execution " + id + " is " + record.status() + ", not awaiting approval");
        }
        if (approver.name().equals(record.requestedBy())) {
            throw ToolException.forbidden("Approval must come from someone other than the requester (" + record.requestedBy() + ")");
        }
        ensureCapacity();
        ExecutionRecord approved = record.decided(ExecutionStatus.QUEUED, approver.name(), note)
                .with(ExecutionStatus.QUEUED, "Approved by " + approver.name() + "; queued");
        save(approved);
        audit.record("approval", approver, "approve_execution", "approved", decisionDetails(id, record, note));
        worker.submit(() -> run(id));
        return approved;
    }

    public synchronized ExecutionRecord reject(String id, Principal approver, String note) {
        ExecutionRecord record = require(id);
        if (record.status() != ExecutionStatus.AWAITING_APPROVAL) {
            throw ToolException.conflict("Execution " + id + " is " + record.status() + ", not awaiting approval");
        }
        ExecutionRecord rejected = record.decided(ExecutionStatus.REJECTED, approver.name(), note)
                .with(ExecutionStatus.REJECTED, "Rejected by " + approver.name());
        save(rejected);
        complete(rejected);
        audit.record("approval", approver, "reject_execution", "rejected", decisionDetails(id, record, note));
        return rejected;
    }

    public ExecutionRecord cancel(String id, Principal caller) {
        Process process;
        ExecutionRecord result;
        synchronized (this) {
            ExecutionRecord record = require(id);
            if (record.status().terminal()) {
                throw ToolException.conflict("Execution " + id + " already finished: " + record.status());
            }
            if (!caller.name().equals(record.requestedBy()) && !caller.atLeast(Role.ADMIN)) {
                throw ToolException.forbidden("Only the requester or an admin can cancel " + id);
            }
            if (record.status() != ExecutionStatus.RUNNING) {
                ExecutionRecord cancelled = record.finished(ExecutionStatus.CANCELLED, null, "Cancelled by " + caller.name() + " before it started",
                        null, Instant.now());
                save(cancelled);
                complete(cancelled);
                return cancelled;
            }
            cancelRequested.add(id);
            process = running.get(id);
            result = record.with(ExecutionStatus.RUNNING, "Cancellation requested by " + caller.name());
        }
        if (process != null) {
            stopTree(process);
        }
        return result;
    }

    /**
     * Registers results produced elsewhere (e.g. CI artifacts unpacked into the project) as a
     * finished execution, so history-based analysis covers CI runs too. Only the known result
     * files are copied; the status comes from the framework's summary like any other run.
     */
    public synchronized ExecutionRecord importResults(Path resultsDir, String label, Principal importer) {
        Path dir = resultsDir.toAbsolutePath().normalize();
        if (!dir.startsWith(config.projectDir()) || dir.startsWith(config.stateDir())) {
            throw ToolException.forbidden("Results must be a directory inside the project (and not the MCP state directory)");
        }
        if (!Files.isRegularFile(dir.resolve("execution-summary.json")) && !Files.isDirectory(dir.resolve("cucumber-reports"))) {
            throw ToolException.invalid("No execution-summary.json or cucumber-reports/ in " + config.projectDir().relativize(dir));
        }
        String id = newId();
        Path results = resultsDir(id);
        Map<String, Object> summary = null;
        try {
            Files.createDirectories(results);
            for (String item : RESULT_ITEMS) {
                Path source = dir.resolve(item);
                if (Files.exists(source)) {
                    copy(source, results.resolve(item));
                }
            }
            Path summaryFile = results.resolve("execution-summary.json");
            if (Files.isRegularFile(summaryFile)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> s = json.readValue(summaryFile.toFile(), LinkedHashMap.class);
                summary = s;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot import results from " + dir, e);
        }
        String env = summary == null ? "unknown" : String.valueOf(summary.getOrDefault("environment", "unknown"));
        String browser = summary == null ? "unknown" : String.valueOf(summary.getOrDefault("browser", "unknown"));
        ExecutionRequest request = new ExecutionRequest("import", label, null, null, env, browser, 0, 0);
        Instant now = Instant.now();
        ExecutionStatus status = decide(summary == null ? null : 0, summary);
        ExecutionRecord record = new ExecutionRecord(id, request, status, importer.name(), null, null, now, now, now,
                null, "Imported from " + config.projectDir().relativize(dir), summary);
        save(record);
        audit.record("import", importer, "import_execution_results", "ok", Map.of("executionId", id, "from", dir.toString()));
        return record;
    }

    // ---------------------------------------------------------------- queries

    public Optional<ExecutionRecord> get(String id) {
        return Optional.ofNullable(records.get(id));
    }

    public List<ExecutionRecord> list(int limit) {
        return records.values().stream()
                .sorted(Comparator.comparing(ExecutionRecord::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    public List<ExecutionRecord> pendingApprovals() {
        return records.values().stream()
                .filter(r -> r.status() == ExecutionStatus.AWAITING_APPROVAL)
                .sorted(Comparator.comparing(ExecutionRecord::createdAt))
                .toList();
    }

    /** Most recent finished run that produced results, if any. */
    public Optional<ExecutionRecord> latestFinished() {
        return list(Integer.MAX_VALUE).stream()
                .filter(r -> r.status().terminal() && Files.isDirectory(resultsDir(r.id())))
                .findFirst();
    }

    /** Waits up to {@code wait} for the run to finish and returns its state either way. */
    public ExecutionRecord await(String id, Duration wait) {
        ExecutionRecord record = require(id);
        if (record.status().terminal() || wait.isZero()) {
            return record;
        }
        CompletableFuture<ExecutionRecord> done = completions.get(id);
        if (done == null) {
            return record;
        }
        try {
            return done.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return require(id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return require(id);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Execution wait failed", e);
        }
    }

    public Path executionDir(String id) {
        return executionsDir.resolve(id);
    }

    public Path resultsDir(String id) {
        return executionDir(id).resolve("results");
    }

    // ---------------------------------------------------------------- running

    private void run(String id) {
        ExecutionRecord record;
        synchronized (this) {
            record = records.get(id);
            if (record == null || record.status() != ExecutionStatus.QUEUED) {
                return;
            }
            record = record.started(Instant.now());
            save(record);
        }
        Path dir = executionDir(id);
        Path console = dir.resolve("console.log");
        Integer exitCode = null;
        ExecutionStatus status = null;
        String message = null;
        try {
            Files.createDirectories(dir);
            ProcessBuilder builder = new ProcessBuilder(commands.command(record.request()))
                    .directory(config.projectDir().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(console.toFile());
            // The framework run must not see the server's own settings (token file location etc.).
            builder.environment().keySet().removeIf(k -> k.startsWith("QA_MCP_"));
            Process process = builder.start();
            running.put(id, process);
            LOG.info("Execution {} started (pid {}): {}", id, process.pid(), record.request());
            if (process.waitFor(config.runTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                exitCode = process.exitValue();
            } else {
                stopTree(process);
                status = ExecutionStatus.TIMED_OUT;
                message = "Stopped after the run timeout of " + config.runTimeout().toMinutes() + " minute(s)";
            }
        } catch (IOException e) {
            status = ExecutionStatus.ERROR;
            message = "Could not start the framework build: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Optional.ofNullable(running.get(id)).ifPresent(ExecutionService::stopTree);
            status = ExecutionStatus.ERROR;
            message = "Server is shutting down; run stopped";
        } finally {
            running.remove(id);
        }
        Map<String, Object> summary = collectResults(id);
        if (cancelRequested.remove(id)) {
            status = ExecutionStatus.CANCELLED;
            message = "Cancelled while running";
        } else if (status == null) {
            status = decide(exitCode, summary);
            message = describe(status, exitCode, summary);
        }
        ExecutionRecord finished;
        synchronized (this) {
            finished = records.get(id).finished(status, exitCode, message, summary, Instant.now());
            save(finished);
        }
        LOG.info("Execution {} finished: {} ({})", id, status, message);
        complete(finished);
    }

    /** Outcome from Maven's exit code and the framework's own counts only. */
    static ExecutionStatus decide(Integer exitCode, Map<String, Object> summary) {
        if (summary == null) {
            return ExecutionStatus.ERROR;
        }
        long total = number(summary.get("total"));
        long failed = number(summary.get("failed"));
        if (total == 0) {
            return ExecutionStatus.NO_TESTS;
        }
        if (failed > 0) {
            return ExecutionStatus.FAILED;
        }
        return exitCode != null && exitCode == 0 ? ExecutionStatus.PASSED : ExecutionStatus.ERROR;
    }

    private static String describe(ExecutionStatus status, Integer exitCode, Map<String, Object> summary) {
        return switch (status) {
            case PASSED, FAILED -> summary.get("passed") + "/" + summary.get("total") + " passed, " + summary.get("failed") + " failed, "
                    + summary.get("skipped") + " skipped";
            case NO_TESTS -> "No scenario matched the selection";
            case ERROR -> summary == null
                    ? "The build failed before tests produced a summary (exit code " + exitCode + "); see console.log"
                    : "Maven exited with " + exitCode + " although no scenario failed; see console.log";
            default -> status.name();
        };
    }

    private Map<String, Object> collectResults(String id) {
        Path target = config.projectDir().resolve("target");
        Path results = resultsDir(id);
        try {
            Files.createDirectories(results);
            for (String item : RESULT_ITEMS) {
                Path source = target.resolve(item);
                if (Files.exists(source)) {
                    copy(source, results.resolve(item));
                }
            }
            Path summaryFile = results.resolve("execution-summary.json");
            if (Files.isRegularFile(summaryFile)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> summary = json.readValue(summaryFile.toFile(), LinkedHashMap.class);
                return summary;
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not collect results for {}: {}", id, e.toString());
        }
        return null;
    }

    private static void copy(Path source, Path destination) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.createDirectories(PathNames.parent(destination));
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path to = destination.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Stops the process and everything it started (Maven forks the test JVM, which starts browsers). */
    static void stopTree(Process process) {
        List<ProcessHandle> children = process.descendants().toList();
        children.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    // ---------------------------------------------------------------- persistence

    private void loadHistory() {
        try (Stream<Path> dirs = Files.list(executionsDir)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                Path file = dir.resolve("execution.json");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    ExecutionRecord record = json.readValue(file.toFile(), ExecutionRecord.class);
                    if (!record.status().terminal()) {
                        // A previous server process died mid-run; its result is unknown, never "passed".
                        record = record.finished(ExecutionStatus.ERROR, null, "Server restarted before this run finished", record.summary(),
                                Instant.now());
                        records.put(record.id(), record);
                        persist(record);
                    } else {
                        records.put(record.id(), record);
                    }
                } catch (RuntimeException e) {
                    LOG.warn("Skipping unreadable execution record {}: {}", file, e.toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read execution history", e);
        }
    }

    private void save(ExecutionRecord record) {
        records.put(record.id(), record);
        persist(record);
    }

    private void persist(ExecutionRecord record) {
        Path dir = executionDir(record.id());
        try {
            Files.createDirectories(dir);
            json.writeValue(dir.resolve("execution.json").toFile(), record);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save execution " + record.id(), e);
        }
    }

    private void complete(ExecutionRecord record) {
        Optional.ofNullable(completions.remove(record.id())).ifPresent(f -> f.complete(record));
    }

    private ExecutionRecord require(String id) {
        ExecutionRecord record = records.get(id);
        if (record == null) {
            throw ToolException.notFound("No execution with id " + id);
        }
        return record;
    }

    private void ensureCapacity() {
        long queued = records.values().stream().filter(r -> r.status() == ExecutionStatus.QUEUED).count();
        if (queued >= config.maxQueuedRuns()) {
            throw ToolException.conflict("Run queue is full (" + queued + " queued). Wait for a run to finish or cancel one.");
        }
    }

    private String newId() {
        byte[] bytes = new byte[3];
        random.nextBytes(bytes);
        return "run-" + ID_TIME.format(Instant.now()) + "-" + HexFormat.of().formatHex(bytes);
    }

    private static Map<String, Object> decisionDetails(String id, ExecutionRecord record, String note) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("executionId", id);
        details.put("requestedBy", record.requestedBy());
        details.put("environment", record.request().environment());
        details.put("note", note == null ? "" : note);
        return details;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    @Override
    public void close() {
        worker.shutdownNow();
        running.values().forEach(ExecutionService::stopTree);
    }
}
