package io.qaplatform.ai.insights;

import io.qaplatform.ai.llm.AiClient;
import io.qaplatform.ai.llm.LlmException;
import io.qaplatform.ai.llm.PromptManager;
import io.qaplatform.mcp.discovery.FeatureIndex;
import io.qaplatform.mcp.discovery.ScenarioInfo;
import io.qaplatform.mcp.discovery.StepDefinitionIndex;
import io.qaplatform.mcp.tools.DiscoveryTools;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Test-suite maintenance findings, each with an id, evidence and a suggested action:
 * unused step definitions, duplicate or near-duplicate scenarios, very long scenarios,
 * single-row outlines, quarantined scenarios, brittle locators in page objects (XPath, CSS
 * classes, nth()), and flaky / consistently failing scenarios from run history. With AI, a
 * prioritised plan that may only reference these finding ids. Nothing is changed automatically.
 */
public final class MaintenanceAdvisor {

    private static final Pattern LOCATOR = Pattern.compile("\\.locator\\(\\s*\"([^\"]+)\"");

    private final Path projectDir;
    private final FeatureIndex features;
    private final StepDefinitionIndex steps;
    private final ExecutionHistory history;
    private final AiClient ai;

    public MaintenanceAdvisor(Path projectDir, FeatureIndex features, StepDefinitionIndex steps, ExecutionHistory history, AiClient ai) {
        this.projectDir = projectDir;
        this.features = features;
        this.steps = steps;
        this.history = history;
        this.ai = ai;
    }

    public Map<String, Object> advise(boolean useAi) {
        List<Map<String, Object>> findings = new ArrayList<>();
        // unused step definitions
        Map<StepDefinitionIndex.StepDefinition, Integer> usage = new LinkedHashMap<>();
        steps.definitions().forEach(d -> usage.put(d, 0));
        for (ScenarioInfo s : features.scenarios()) {
            Stream.concat(s.backgroundSteps().stream(), s.steps().stream())
                    .flatMap(st -> s.concreteTexts(st).stream())
                    .flatMap(t -> steps.match(t).stream())
                    .forEach(m -> usage.merge(m.definition(), 1, Integer::sum));
        }
        usage.forEach((d, n) -> {
            if (n == 0) {
                add(findings, "unused-step", "low", d.file() + ":" + d.line(), "Step definition '" + d.expression() + "' is not used by any feature",
                        "Delete it, or add the scenario it was written for");
            }
        });
        // duplicates and near-duplicates
        List<ScenarioInfo> all = features.scenarios();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                double sim = all.get(i).normalizedSteps().equals(all.get(j).normalizedSteps()) ? 1.0
                        : DiscoveryTools.jaccard(all.get(i).normalizedSteps(), all.get(j).normalizedSteps());
                if (sim >= 0.85) {
                    add(findings, "duplicate-scenario", sim == 1.0 ? "medium" : "low", all.get(i).id() + " ~ " + all.get(j).id(),
                            "'" + all.get(i).name() + "' and '" + all.get(j).name() + "' have " + Math.round(sim * 100) + "% the same steps",
                            "Merge into one Scenario Outline with an Examples row per case");
                }
            }
        }
        for (ScenarioInfo s : all) {
            if (s.steps().size() > 12) {
                add(findings, "long-scenario", "low", s.id(), "'" + s.name() + "' has " + s.steps().size() + " steps",
                        "Split it, or move set-up into a Background or an API-based Given step");
            }
            if (!s.examples().isEmpty() && s.runCount() == 1) {
                add(findings, "single-row-outline", "low", s.id(), "Outline '" + s.name() + "' has one example row",
                        "Add boundary and negative rows, or turn it into a plain Scenario");
            }
            if (s.tags().contains("@quarantine")) {
                add(findings, "quarantined", "high", s.id(), "'" + s.name() + "' is quarantined and never runs",
                        "Fix the cause and remove @quarantine, or delete the scenario; quarantine should be temporary");
            }
        }
        // brittle locators
        for (String dir : List.of("src/main/java/io/qaplatform/ui/pages", "src/main/java/io/qaplatform/ui/components")) {
            for (Path file : javaFiles(projectDir.resolve(dir))) {
                String source = read(file);
                Matcher m = LOCATOR.matcher(source);
                while (m.find()) {
                    String selector = m.group(1);
                    String why = selector.startsWith("//") || selector.startsWith("xpath=") ? "XPath"
                            : selector.matches(".*\\.[a-zA-Z][\\w-]*.*") && !selector.contains("[data-testid") ? "CSS class"
                            : selector.contains(":nth-") ? "positional CSS" : null;
                    if (why != null) {
                        int line = source.substring(0, m.start()).split("\n", -1).length;
                        add(findings, "brittle-locator", "medium", projectDir.relativize(file) + ":" + line, why + " locator \"" + selector + "\"",
                                "Prefer getByRole / getByLabel / getByTestId (see the locator rules in the README)");
                    }
                }
                Matcher nth = Pattern.compile("\\.nth\\(\\d+\\)").matcher(source);
                while (nth.find()) {
                    int line = source.substring(0, nth.start()).split("\n", -1).length;
                    add(findings, "brittle-locator", "low", projectDir.relativize(file) + ":" + line, "Positional nth() locator",
                            "Locate by role and accessible name instead of position");
                }
            }
        }
        // history
        for (Map<String, Object> row : new FlakyAnalyzer().analyze(history.outcomes(50), 2)) {
            String c = String.valueOf(row.get("classification"));
            if ("FLAKY".equals(c)) {
                add(findings, "flaky", "high", String.valueOf(row.get("scenario")), "Flaky: history " + row.get("history") + ", score " + row.get("flakinessScore"),
                        "Analyse with analyze_scenario_failure; fix waits/data isolation; quarantine only with a ticket");
            } else if ("CONSISTENTLY_FAILING".equals(c)) {
                add(findings, "consistently-failing", "high", String.valueOf(row.get("scenario")), "Failed in the last runs: history " + row.get("history"),
                        "Treat as a defect or a broken test, not as flakiness: analyse and fix");
            }
        }
        Map<String, Integer> byType = new HashMap<>();
        findings.forEach(f -> byType.merge(String.valueOf(f.get("type")), 1, Integer::sum));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("findings", findings);
        result.put("countsByType", byType);
        if (useAi) {
            result.put("aiPlan", ai.enabled() ? plan(findings) : Map.of("available", false, "reason", "AI provider not configured"));
        }
        result.put("note", "Findings are suggestions. Nothing is changed automatically; mandatory regression scenarios are never skipped.");
        return result;
    }

    private Map<String, Object> plan(List<Map<String, Object>> findings) {
        if (findings.isEmpty()) {
            return Map.of("available", true, "steps", List.of(), "note", "No findings");
        }
        try {
            JsonNode j = ai.json("maintenance-plan", Map.of("findings",
                    PromptManager.untrusted("maintenance findings", findings.stream().limit(80).map(Object::toString).reduce("", (a, b) -> a + b + "\n"), 12000))).json();
            Set<String> ids = new java.util.HashSet<>();
            findings.forEach(f -> ids.add(String.valueOf(f.get("id"))));
            List<Map<String, Object>> steps = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            for (JsonNode s : j.path("steps")) {
                List<String> refs = new ArrayList<>();
                s.path("finding_ids").forEach(r -> refs.add(r.asString("")));
                if (refs.isEmpty() || !ids.containsAll(refs)) {
                    dropped.add(s.path("title").asString(""));
                    continue;
                }
                steps.add(Map.of("title", s.path("title").asString(""), "findingIds", refs, "why", s.path("why").asString("")));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("available", true);
            m.put("status", "SUGGESTED PLAN (review before acting)");
            m.put("steps", steps);
            if (!dropped.isEmpty()) {
                m.put("droppedSteps", dropped);
                m.put("warning", "Steps that did not reference real finding ids were dropped");
            }
            return m;
        } catch (LlmException e) {
            return Map.of("available", false, "reason", e.kind() + ": " + e.getMessage());
        }
    }

    private static void add(List<Map<String, Object>> findings, String type, String severity, String where, String what, String action) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", type + "-" + (findings.size() + 1));
        f.put("type", type);
        f.put("severity", severity);
        f.put("where", where);
        f.put("finding", what);
        f.put("suggestedAction", action);
        findings.add(f);
    }

    private static List<Path> javaFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
