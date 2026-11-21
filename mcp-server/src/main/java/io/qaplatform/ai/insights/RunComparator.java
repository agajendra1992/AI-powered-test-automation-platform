package io.qaplatform.ai.insights;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compares a targeted run with a full-regression run (step 9 of the selection workflow): which
 * failures the full run found that the selection did not run ("escaped"), which it caught, and
 * which failed only in one of them.
 */
public final class RunComparator {

    public Map<String, Object> compare(String selectedId, List<ExecutionHistory.Outcome> selected, String fullId,
                                       List<ExecutionHistory.Outcome> full) {
        Set<String> selectedNames = names(selected, null);
        Set<String> fullFailures = names(full, "failed");
        Set<String> selectedFailures = names(selected, "failed");
        List<String> escaped = fullFailures.stream().filter(s -> !selectedNames.contains(s)).sorted().toList();
        List<String> caught = fullFailures.stream().filter(selectedFailures::contains).sorted().toList();
        List<String> passedInSelectionFailedInFull = fullFailures.stream()
                .filter(s -> selectedNames.contains(s) && !selectedFailures.contains(s)).sorted().toList();
        List<String> failedOnlyInSelection = selectedFailures.stream().filter(s -> !fullFailures.contains(s)).sorted().toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selectedRun", selectedId);
        m.put("fullRun", fullId);
        m.put("selectedScenarios", selectedNames.size());
        m.put("fullScenarios", names(full, null).size());
        m.put("fullRunFailures", fullFailures.size());
        m.put("caughtBySelection", caught);
        m.put("escapedSelection", escaped);
        m.put("differentResult", Map.of("passedInSelectionButFailedInFull", passedInSelectionFailedInFull,
                "failedOnlyInSelection", failedOnlyInSelection));
        m.put("verdict", fullFailures.isEmpty() ? "Full regression found no failures: nothing could escape."
                : escaped.isEmpty() ? "The selection caught every failure the full regression found."
                : escaped.size() + " failure(s) found only by the full regression: widen the selection rules for these areas.");
        if (!passedInSelectionFailedInFull.isEmpty() || !failedOnlyInSelection.isEmpty()) {
            m.put("note", "Scenarios with different results in the two runs are flaky or environment-dependent; check get_flaky_tests.");
        }
        return m;
    }

    private static Set<String> names(List<ExecutionHistory.Outcome> outcomes, String status) {
        return outcomes.stream().filter(o -> status == null || status.equals(o.status()))
                .map(ExecutionHistory.Outcome::scenario).collect(Collectors.toSet());
    }
}
