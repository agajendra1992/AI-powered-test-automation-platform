package io.qaplatform.ai.insights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Flaky-test analytics over run history. Per scenario: runs, passes, failures, passed-after-retry,
 * status flips between consecutive runs, distinct error signatures, and a classification:
 * <ul>
 *   <li>FLAKY: passed after retry at least once, or alternates between pass and fail (≥ 2 flips)</li>
 *   <li>CONSISTENTLY_FAILING: the last 2+ runs failed with no pass in between (likely a real defect or a broken test, not flakiness)</li>
 *   <li>RECOVERED: failed earlier, passing in the last 2 runs</li>
 *   <li>STABLE: otherwise</li>
 * </ul>
 * It only reports: quarantining or fixing is a human decision.
 */
public final class FlakyAnalyzer {

    public List<Map<String, Object>> analyze(List<ExecutionHistory.Outcome> outcomes, int minRuns) {
        Map<String, List<ExecutionHistory.Outcome>> byScenario = new LinkedHashMap<>();
        outcomes.stream().sorted(Comparator.comparing(ExecutionHistory.Outcome::time))
                .forEach(o -> byScenario.computeIfAbsent(o.scenario(), k -> new ArrayList<>()).add(o));
        List<Map<String, Object>> rows = new ArrayList<>();
        byScenario.forEach((scenario, list) -> {
            List<ExecutionHistory.Outcome> executed = list.stream().filter(o -> !"skipped".equals(o.status())).toList();
            if (executed.size() < minRuns) {
                return;
            }
            long passes = executed.stream().filter(o -> "passed".equals(o.status())).count();
            long fails = executed.stream().filter(o -> "failed".equals(o.status())).count();
            long retried = executed.stream().filter(o -> "flaky".equals(o.status())).count();
            int flips = 0;
            for (int i = 1; i < executed.size(); i++) {
                if (ok(executed.get(i)) != ok(executed.get(i - 1))) {
                    flips++;
                }
            }
            int n = executed.size();
            boolean lastTwoFailed = n >= 2 && !ok(executed.get(n - 1)) && !ok(executed.get(n - 2));
            boolean lastTwoPassed = n >= 2 && "passed".equals(executed.get(n - 1).status()) && "passed".equals(executed.get(n - 2).status());
            String classification;
            if (retried > 0 || flips >= 2) {
                classification = "FLAKY";
            } else if (lastTwoFailed) {
                classification = "CONSISTENTLY_FAILING";
            } else if (fails > 0 && lastTwoPassed) {
                classification = "RECOVERED";
            } else {
                classification = "STABLE";
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scenario", scenario);
            row.put("classification", classification);
            row.put("runs", n);
            row.put("passed", passes);
            row.put("failed", fails);
            row.put("passedAfterRetry", retried);
            row.put("statusFlips", flips);
            row.put("flakinessScore", Math.round((retried + flips) * 100.0 / n) / 100.0);
            row.put("history", executed.stream().map(o -> "flaky".equals(o.status()) ? "F" : String.valueOf(o.status().charAt(0)))
                    .reduce("", String::concat));
            row.put("distinctErrors", executed.stream().filter(o -> o.signature() != null).map(ExecutionHistory.Outcome::signature)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)).size());
            row.put("environments", executed.stream().map(ExecutionHistory.Outcome::environment).distinct().toList());
            row.put("browsers", executed.stream().map(ExecutionHistory.Outcome::browser).distinct().toList());
            row.put("lastRun", executed.get(n - 1).executionId());
            rows.add(row);
        });
        rows.sort(Comparator.comparing((Map<String, Object> r) -> order(String.valueOf(r.get("classification"))))
                .thenComparing(r -> -((Double) r.get("flakinessScore"))));
        return rows;
    }

    private static boolean ok(ExecutionHistory.Outcome o) {
        return "passed".equals(o.status());
    }

    private static int order(String c) {
        return switch (c) {
            case "FLAKY" -> 0;
            case "CONSISTENTLY_FAILING" -> 1;
            case "RECOVERED" -> 2;
            default -> 3;
        };
    }
}
