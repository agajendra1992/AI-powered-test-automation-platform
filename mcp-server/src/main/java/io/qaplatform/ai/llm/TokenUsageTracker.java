package io.qaplatform.ai.llm;

import io.qaplatform.mcp.util.PathNames;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Records every model call (provider, model, purpose, tokens, latency, outcome) to
 * {@code <state>/ai-usage.jsonl} and enforces a daily token budget (UTC day).
 * No prompt or response text is stored.
 */
public final class TokenUsageTracker {

    public record Usage(Instant time, String provider, String model, String purpose, long inputTokens, long outputTokens,
                        long latencyMs, String outcome) {
    }

    private final Path file;
    private final long dailyBudget;
    private final JsonMapper json = JsonMapper.builder().build();

    public TokenUsageTracker(Path file, long dailyBudget) {
        this.file = file;
        this.dailyBudget = dailyBudget;
        try {
            Files.createDirectories(PathNames.parent(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void record(Usage usage) {
        try {
            Files.writeString(file, json.writeValueAsString(usage) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    public long dailyBudget() {
        return dailyBudget;
    }

    public long usedToday() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return all().stream().filter(u -> LocalDate.ofInstant(u.time(), ZoneOffset.UTC).equals(today))
                .mapToLong(u -> u.inputTokens() + u.outputTokens()).sum();
    }

    public void checkBudget() {
        long used = usedToday();
        if (used >= dailyBudget) {
            throw new LlmException(LlmException.Kind.BUDGET_EXCEEDED,
                    "Daily AI token budget used (" + used + " of " + dailyBudget + "); raise QA_AI_DAILY_TOKEN_BUDGET or wait until 00:00 UTC");
        }
    }

    public synchronized List<Usage> all() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank()).map(l -> json.readValue(l, Usage.class)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Totals per day and per purpose/model, for the get_ai_usage tool. */
    public Map<String, Object> report() {
        List<Usage> usages = all();
        Map<String, long[]> byPurpose = new TreeMap<>();
        Map<String, long[]> byDay = new TreeMap<>();
        for (Usage u : usages) {
            String key = u.provider() + "/" + u.model() + " " + u.purpose();
            add(byPurpose, key, u);
            add(byDay, LocalDate.ofInstant(u.time(), ZoneOffset.UTC).toString(), u);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("usedTodayTokens", usedToday());
        report.put("dailyBudgetTokens", dailyBudget);
        report.put("calls", usages.size());
        report.put("byPurpose", table(byPurpose));
        report.put("byDayUtc", table(byDay));
        report.put("note", "Token counts as reported by the provider. No cost estimate: prices vary by provider, model and contract.");
        return report;
    }

    private static void add(Map<String, long[]> map, String key, Usage u) {
        long[] t = map.computeIfAbsent(key, k -> new long[4]);
        t[0]++;
        t[1] += u.inputTokens();
        t[2] += u.outputTokens();
        if (!"ok".equals(u.outcome())) {
            t[3]++;
        }
    }

    private static List<Map<String, Object>> table(Map<String, long[]> map) {
        return map.entrySet().stream().map(e -> Map.<String, Object>of("key", e.getKey(), "calls", e.getValue()[0],
                "inputTokens", e.getValue()[1], "outputTokens", e.getValue()[2], "failedCalls", e.getValue()[3])).toList();
    }
}
