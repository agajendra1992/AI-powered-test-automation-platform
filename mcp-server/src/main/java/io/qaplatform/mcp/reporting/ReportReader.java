package io.qaplatform.mcp.reporting;

import io.qaplatform.mcp.util.PathNames;
import io.qaplatform.mcp.execution.ExecutionRecord;
import io.qaplatform.mcp.execution.ExecutionService;
import io.qaplatform.mcp.tools.ToolException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Reads the framework's machine-readable outputs for one run: execution-summary.json,
 * test-failures/*.json, Cucumber JSON, Allure results (screenshots and trace attachments) and
 * the execution log. Reads only; every path is resolved inside the run's results directory.
 *
 * <p>Run selector: an execution id, {@code "latest"} (most recent finished MCP run), or
 * {@code "workspace"} (the project's current {@code target/}, e.g. a run started outside MCP or
 * CI artifacts unpacked there).
 */
public final class ReportReader {

    public static final String WORKSPACE = "workspace";
    public static final String LATEST = "latest";
    private static final int MAX_STACK_LINES = 25;
    private static final long MAX_IMAGE_BYTES = 4L * 1024 * 1024;

    public record Source(String selector, Path dir, ExecutionRecord execution) {
    }

    private final Path projectDir;
    private final ExecutionService executions;
    private final JsonMapper json = JsonMapper.builder().build();

    public ReportReader(Path projectDir, ExecutionService executions) {
        this.projectDir = projectDir;
        this.executions = executions;
    }

    public Source resolve(String selector) {
        String s = selector == null ? LATEST : selector;
        if (WORKSPACE.equals(s)) {
            return new Source(WORKSPACE, projectDir.resolve("target"), null);
        }
        if (LATEST.equals(s)) {
            return executions.latestFinished()
                    .map(r -> new Source(r.id(), executions.resultsDir(r.id()), r))
                    .orElseGet(() -> new Source(WORKSPACE, projectDir.resolve("target"), null));
        }
        if (!ExecutionService.ID.matcher(s).matches()) {
            throw ToolException.invalid("execution_id must be 'latest', 'workspace' or an id like run-20261119-101500-a1b2c3");
        }
        ExecutionRecord record = executions.get(s).orElseThrow(() -> ToolException.notFound("No execution with id " + s));
        return new Source(s, executions.resultsDir(s), record);
    }

    // ------------------------------------------------------------------ summary and failures

    /**
     * The run's summary without its per-scenario list ({@code scenarios}, kept for the execution
     * history report): it grows with the suite, and failures come from {@link #failures}.
     */
    public Map<String, Object> summary(Source source) {
        Path file = source.dir().resolve("execution-summary.json");
        if (!Files.isRegularFile(file)) {
            throw ToolException.notFound("No execution summary for " + source.selector()
                    + (source.execution() == null ? "" : " (status " + source.execution().status() + ")"));
        }
        Map<String, Object> summary = readMap(file);
        summary.remove("scenarios");
        return summary;
    }

    public List<Map<String, Object>> failures(Source source) {
        Path dir = source.dir().resolve("test-failures");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Path file : list(dir, ".json")) {
            Map<String, Object> record = readMap(file);
            Object stack = record.get("stackTrace");
            if (stack instanceof String s) {
                record.put("stackTrace", firstLines(s, MAX_STACK_LINES));
            }
            result.add(record);
        }
        result.sort(Comparator.comparing(m -> String.valueOf(m.get("timestamp"))));
        return result;
    }

    // ------------------------------------------------------------------ Cucumber

    /** One row per executed scenario (outline rows separately) from the Cucumber JSON reports. */
    public List<Map<String, Object>> cucumberScenarios(Source source) {
        Path dir = source.dir().resolve("cucumber-reports");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Path file : list(dir, ".json")) {
            JsonNode features = json.readTree(file.toFile());
            for (JsonNode feature : features) {
                for (JsonNode element : feature.path("elements")) {
                    if (!"scenario".equals(element.path("type").asString(""))) {
                        continue;
                    }
                    rows.add(scenarioRow(PathNames.name(file), feature, element));
                }
            }
        }
        return rows;
    }

    private static Map<String, Object> scenarioRow(String report, JsonNode feature, JsonNode element) {
        String status = "passed";
        String failedStep = null;
        String error = null;
        long durationNs = 0;
        List<JsonNode> results = new ArrayList<>();
        element.path("before").forEach(h -> results.add(h));
        element.path("steps").forEach(s -> results.add(s));
        element.path("after").forEach(h -> results.add(h));
        for (JsonNode step : results) {
            JsonNode result = step.path("result");
            durationNs += result.path("duration").asLong(0);
            String stepStatus = result.path("status").asString("unknown");
            if (!"passed".equals(stepStatus) && "passed".equals(status)) {
                status = stepStatus;
                failedStep = step.has("name")
                        ? step.path("keyword").asString("").trim() + " " + step.path("name").asString("")
                        : "hook " + step.path("match").path("location").asString("");
                error = firstLines(result.path("error_message").asString(""), 3);
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("report", report);
        row.put("feature", feature.path("name").asString(""));
        row.put("scenario", element.path("name").asString(""));
        row.put("uri", feature.path("uri").asString(""));
        row.put("line", element.path("line").asInt());
        List<String> tags = new ArrayList<>();
        element.path("tags").forEach(t -> tags.add(t.path("name").asString("")));
        row.put("tags", tags);
        row.put("status", status);
        row.put("durationMs", durationNs / 1_000_000);
        if (failedStep != null) {
            row.put("failedStep", failedStep);
            row.put("error", error);
        }
        return row;
    }

    // ------------------------------------------------------------------ Allure

    public Map<String, Object> allureOverview(Source source) {
        Path dir = source.dir().resolve("allure-results");
        Map<String, Integer> byStatus = new TreeMap<>();
        int attachments = 0;
        for (Path file : list(dir, "-result.json")) {
            JsonNode node = json.readTree(file.toFile());
            byStatus.merge(node.path("status").asString("unknown"), 1, Integer::sum);
        }
        try (Stream<Path> files = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            attachments = (int) files.filter(p -> PathNames.name(p).contains("-attachment")).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("resultsDirectory", projectDir.relativize(dir).toString());
        overview.put("results", byStatus.values().stream().mapToInt(Integer::intValue).sum());
        overview.put("byStatus", byStatus);
        overview.put("attachments", attachments);
        Path report = projectDir.resolve("target/allure-report/index.html");
        overview.put("htmlReport", WORKSPACE.equals(source.selector()) && Files.isRegularFile(report)
                ? projectDir.relativize(report).toString() : null);
        overview.put("howToBuildHtml", "allure generate " + projectDir.relativize(dir) + " -o target/allure-report --clean"
                + " (or scripts/allure-report.sh for the workspace results)");
        return overview;
    }

    /** Latest Allure result for a scenario (retries create several; the last attempt wins). */
    public Optional<JsonNode> allureResult(Source source, String scenarioName) {
        Path dir = source.dir().resolve("allure-results");
        JsonNode best = null;
        for (Path file : list(dir, "-result.json")) {
            JsonNode node = json.readTree(file.toFile());
            if (scenarioName.equals(node.path("name").asString(null))
                    && (best == null || node.path("stop").asLong(0) > best.path("stop").asLong(0))) {
                best = node;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Attachments of the given MIME type for a result, in order: those on the result and its steps,
     * then those added by Cucumber hooks. Hook attachments (the framework's failure screenshot,
     * diagnostics and trace come from its @After hook) live in Allure "container" files whose
     * {@code children} reference the result's uuid, under {@code befores}/{@code afters}.
     */
    public List<Path> attachments(Source source, JsonNode result, String mimeType) {
        Path dir = source.dir().resolve("allure-results");
        List<Path> found = new ArrayList<>();
        collectAttachments(dir, result, mimeType, found);
        String uuid = result.path("uuid").asString("");
        for (Path file : list(dir, "-container.json")) {
            JsonNode container = json.readTree(file.toFile());
            boolean ownsResult = false;
            for (JsonNode child : container.path("children")) {
                ownsResult |= uuid.equals(child.asString(""));
            }
            if (!ownsResult) {
                continue;
            }
            for (String phase : List.of("befores", "afters")) {
                for (JsonNode fixture : container.path(phase)) {
                    collectAttachments(dir, fixture, mimeType, found);
                }
            }
        }
        return found;
    }

    /**
     * Text attachments (name → content, each capped) for a result, including hook attachments:
     * the framework's "Browser diagnostics" and "API calls in this scenario" live here.
     */
    public Map<String, String> textAttachments(Source source, JsonNode result, int maxCharsEach) {
        Map<String, String> texts = new LinkedHashMap<>();
        for (String type : List.of("text/plain", "application/json")) {
            Path dir = source.dir().resolve("allure-results");
            List<JsonNode> owners = new ArrayList<>();
            owners.add(result);
            String uuid = result.path("uuid").asString("");
            for (Path file : list(dir, "-container.json")) {
                JsonNode container = json.readTree(file.toFile());
                for (JsonNode child : container.path("children")) {
                    if (uuid.equals(child.asString(""))) {
                        container.path("befores").forEach(owners::add);
                        container.path("afters").forEach(owners::add);
                    }
                }
            }
            for (JsonNode owner : owners) {
                collectNamed(dir, owner, type, texts, maxCharsEach);
            }
        }
        return texts;
    }

    private static void collectNamed(Path dir, JsonNode node, String mimeType, Map<String, String> out, int maxChars) {
        for (JsonNode a : node.path("attachments")) {
            if (!mimeType.equals(a.path("type").asString(""))) {
                continue;
            }
            Path file = dir.resolve(a.path("source").asString("")).normalize();
            if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                String name = a.path("name").asString("attachment");
                String key = out.containsKey(name) ? name + " (" + (out.size() + 1) + ")" : name;
                out.put(key, text.length() > maxChars ? text.substring(0, maxChars) + "\n[truncated]" : text);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (JsonNode step : node.path("steps")) {
            collectNamed(dir, step, mimeType, out, maxChars);
        }
    }

    private static void collectAttachments(Path dir, JsonNode node, String mimeType, List<Path> found) {
        for (JsonNode a : node.path("attachments")) {
            if (mimeType.equals(a.path("type").asString(""))) {
                Path file = dir.resolve(a.path("source").asString("")).normalize();
                if (file.startsWith(dir) && Files.isRegularFile(file)) {
                    found.add(file);
                }
            }
        }
        for (JsonNode step : node.path("steps")) {
            collectAttachments(dir, step, mimeType, found);
        }
    }

    public byte[] image(Path file) {
        try {
            if (Files.size(file) > MAX_IMAGE_BYTES) {
                throw ToolException.conflict("Screenshot is larger than " + MAX_IMAGE_BYTES / (1024 * 1024) + " MB; open it from " + file);
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Basic facts about a Playwright trace archive (size and contents), without unpacking it. */
    public Map<String, Object> traceInfo(Path trace) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("file", projectDir.relativize(trace).toString());
        try (ZipFile zip = new ZipFile(trace.toFile())) {
            info.put("sizeBytes", Files.size(trace));
            List<String> entries = zip.stream().map(e -> e.getName()).toList();
            info.put("entries", entries.size());
            info.put("screenshotFrames", entries.stream().filter(n -> n.endsWith(".jpeg") || n.endsWith(".png")).count());
            info.put("networkLog", entries.stream().anyMatch(n -> n.endsWith(".network")));
        } catch (IOException e) {
            info.put("error", "Unreadable trace archive: " + e.getMessage());
        }
        info.put("open", "npx playwright show-trace " + projectDir.relativize(trace));
        return info;
    }

    // ------------------------------------------------------------------ logs

    /** Lines of target/logs/execution.log written while the given scenario ran (MDC scenario name). */
    public List<String> scenarioLog(Source source, String scenarioName, int maxLines) {
        Path log = source.dir().resolve("logs/execution.log");
        if (!Files.isRegularFile(log)) {
            throw ToolException.notFound("No execution log for " + source.selector());
        }
        String marker = "[" + scenarioName + "]";
        try (Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
            List<String> matching = lines.filter(l -> l.contains(marker)).toList();
            return matching.size() <= maxLines ? matching : matching.subList(matching.size() - maxLines, matching.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Last lines of the Maven console output of an MCP run. */
    public List<String> consoleTail(String executionId, int maxLines) {
        Path console = executions.executionDir(executionId).resolve("console.log");
        if (!Files.isRegularFile(console)) {
            return List.of();
        }
        try {
            List<String> all = Files.readAllLines(console, StandardCharsets.UTF_8);
            return all.size() <= maxLines ? all : all.subList(all.size() - maxLines, all.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> readMap(Path file) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = json.readValue(file.toFile(), LinkedHashMap.class);
        return map;
    }

    private static List<Path> list(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> PathNames.name(p).endsWith(suffix)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String firstLines(String text, int max) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\\R");
        return lines.length <= max ? text : String.join("\n", Arrays.copyOf(lines, max)) + "\n... (" + (lines.length - max) + " more lines)";
    }
}
