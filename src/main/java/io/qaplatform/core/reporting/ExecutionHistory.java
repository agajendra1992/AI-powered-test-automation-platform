package io.qaplatform.core.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Keeps a copy of every run's execution summary outside {@code target/}, so {@code mvn clean}
 * does not erase it. {@code scripts/execution_history.py report} turns the kept runs into the
 * execution history report: last N days and month by month (docs/EXECUTION_HISTORY.md).
 * <p>
 * Layout: {@code <dir>/runs/<finishedAt>-<suite>.json}, the time in UTC. Runs older than
 * {@code keepDays} are removed, judged by the date at the start of the file name. Recording never
 * fails a run: problems are logged and ignored.
 */
public final class ExecutionHistory {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionHistory.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    public static final String RUNS = "runs";

    private ExecutionHistory() {
    }

    /** Writes the summary as a new run and removes runs older than {@code keepDays}. Returns the file, or null. */
    public static Path record(Path dir, String finishedAt, String suite, String summaryJson, int keepDays) {
        try {
            Path runs = dir.resolve(RUNS);
            Files.createDirectories(runs);
            Path file = runs.resolve(fileName(finishedAt, suite));
            Files.writeString(file, summaryJson);
            prune(runs, keepDays, LocalDate.now(ZoneOffset.UTC));
            return file;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not record the run in the execution history at {}: {}", dir, e.getMessage());
            return null;
        }
    }

    /** {@code 2026-11-25T02:04:47.408Z} + {@code Regression} becomes {@code 20261125T020447408Z-regression.json}. */
    public static String fileName(String finishedAt, String suite) {
        String time = finishedAt.replaceAll("[^0-9TZ]", "");
        String slug = suite.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return time + "-" + (slug.isEmpty() ? "run" : slug) + ".json";
    }

    /** Deletes run files dated before {@code today - keepDays}. Files whose name has no date are left alone. */
    public static void prune(Path runs, int keepDays, LocalDate today) throws IOException {
        if (keepDays <= 0) {
            return;
        }
        String cutoff = today.minusDays(keepDays).format(DAY);
        List<Path> old;
        try (Stream<Path> listing = Files.list(runs)) {
            old = listing.filter(p -> {
                Path fileName = p.getFileName();
                String name = fileName == null ? "" : fileName.toString();
                return name.endsWith(".json") && name.length() > 8 && name.substring(0, 8).matches("\\d{8}")
                        && name.substring(0, 8).compareTo(cutoff) < 0;
            }).toList();
        }
        for (Path file : old) {
            Files.deleteIfExists(file);
        }
    }
}
