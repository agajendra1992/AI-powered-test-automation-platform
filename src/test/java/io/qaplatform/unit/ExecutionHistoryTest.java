package io.qaplatform.unit;

import io.qaplatform.core.reporting.ExecutionHistory;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class ExecutionHistoryTest {

    private static List<String> names(Path runs) throws IOException {
        try (Stream<Path> files = Files.list(runs)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    public void recordsTheSummaryUnderATimeSortableName() throws IOException {
        Path dir = Files.createTempDirectory("history");
        Path file = ExecutionHistory.record(dir, "2026-11-25T02:04:47.408Z", "Framework Regression", "{\"total\":3}", 400);

        assertThat(file).isNotNull();
        assertThat(file.getFileName().toString()).isEqualTo("20261125T020447408Z-framework-regression.json");
        assertThat(Files.readString(file)).isEqualTo("{\"total\":3}");
    }

    @Test
    public void removesOnlyRunsOlderThanTheRetention() throws IOException {
        Path runs = Files.createTempDirectory("runs");
        for (String name : List.of("20261020T100000Z-smoke.json", "20261024T235959Z-smoke.json",
                "20261025T000000Z-smoke.json", "20261125T100000Z-smoke.json", "notes.json")) {
            Files.writeString(runs.resolve(name), "{}");
        }

        ExecutionHistory.prune(runs, 31, LocalDate.of(2026, 11, 25));

        assertThat(names(runs)).containsExactly(
                "20261025T000000Z-smoke.json", "20261125T100000Z-smoke.json", "notes.json");
    }

    @Test
    public void aProblemIsReportedAsNullNotThrown() throws IOException {
        Path notADirectory = Files.createTempFile("history", ".txt");

        assertThat(ExecutionHistory.record(notADirectory, "2026-11-25T00:00:00Z", "Smoke", "{}", 400)).isNull();
    }
}
