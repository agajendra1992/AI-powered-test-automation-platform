package io.qaplatform.ai.insights;

import io.qaplatform.ai.analysis.FailureEvidence;
import io.qaplatform.ai.analysis.RuleClassifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Historical failure correlation: groups failures across runs by normalised error signature (ids,
 * numbers and uuids removed). A cluster hitting many scenarios in one run points at a shared
 * cause (environment, data, a broken shared step); a cluster recurring across runs is a known issue.
 */
public final class FailureCorrelator {

    private final RuleClassifier rules = new RuleClassifier();

    public List<Map<String, Object>> clusters(List<ExecutionHistory.Outcome> outcomes) {
        Map<String, List<ExecutionHistory.Outcome>> bySignature = new LinkedHashMap<>();
        for (ExecutionHistory.Outcome o : outcomes) {
            if (o.signature() != null && !"passed".equals(o.status())) {
                bySignature.computeIfAbsent(o.signature(), k -> new ArrayList<>()).add(o);
            }
        }
        List<Map<String, Object>> clusters = new ArrayList<>();
        bySignature.forEach((signature, list) -> {
            ExecutionHistory.Outcome sample = list.get(0);
            Set<String> scenarios = new LinkedHashSet<>();
            Set<String> runs = new LinkedHashSet<>();
            list.forEach(o -> {
                scenarios.add(o.scenario());
                runs.add(o.executionId());
            });
            int maxScenariosInOneRun = runs.stream()
                    .mapToInt(run -> (int) list.stream().filter(o -> o.executionId().equals(run)).map(ExecutionHistory.Outcome::scenario).distinct().count())
                    .max().orElse(0);
            RuleClassifier.Classification c = rules.classify(new FailureEvidence(sample.executionId(), sample.scenario(), sample.environment(),
                    sample.browser(), sample.errorType(), sample.message(), "", null, 0, false, null, null, List.of(), List.of()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("signature", signature);
            m.put("probableCategory", c.category().name());
            m.put("occurrences", list.size());
            m.put("runs", runs.size());
            m.put("scenarios", List.copyOf(scenarios));
            m.put("environments", list.stream().map(ExecutionHistory.Outcome::environment).distinct().toList());
            m.put("browsers", list.stream().map(ExecutionHistory.Outcome::browser).distinct().toList());
            m.put("firstSeen", list.stream().map(ExecutionHistory.Outcome::time).min(Comparator.naturalOrder()).map(String::valueOf).orElse(""));
            m.put("lastSeen", list.stream().map(ExecutionHistory.Outcome::time).max(Comparator.naturalOrder()).map(String::valueOf).orElse(""));
            List<String> insight = new ArrayList<>();
            if (maxScenariosInOneRun >= 3) {
                insight.add(maxScenariosInOneRun + " scenarios failed the same way in one run: probably one shared cause (environment, data, shared step)");
            }
            if (runs.size() >= 2) {
                insight.add("recurring in " + runs.size() + " runs: a known issue; check whether it is tracked");
            }
            if (list.stream().map(ExecutionHistory.Outcome::browser).distinct().count() == 1 && runs.size() >= 2) {
                insight.add("only seen on " + sample.browser());
            }
            m.put("insights", insight);
            clusters.add(m);
        });
        clusters.sort(Comparator.comparing((Map<String, Object> m) -> -((Integer) m.get("occurrences"))));
        return clusters;
    }
}
