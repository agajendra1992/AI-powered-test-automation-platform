package io.qaplatform.mcp.tools;

import io.cucumber.tagexpressions.Expression;
import io.cucumber.tagexpressions.TagExpressionParser;
import io.qaplatform.mcp.discovery.FeatureIndex;
import io.qaplatform.mcp.discovery.ScenarioInfo;
import io.qaplatform.mcp.discovery.StepDefinitionIndex;
import io.qaplatform.mcp.security.Role;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Read-only Cucumber discovery: features, scenarios, tags, step definitions and duplicates. */
public final class DiscoveryTools {

    static final Pattern SCENARIO_ID = Pattern.compile("[A-Za-z0-9_./-]+\\.feature:\\d{1,6}");
    private static final Pattern RELATIVE_PATH = Pattern.compile("[A-Za-z0-9_./-]+");

    private final FeatureIndex features;
    private final StepDefinitionIndex steps;

    public DiscoveryTools(FeatureIndex features, StepDefinitionIndex steps) {
        this.features = features;
        this.steps = steps;
    }

    public List<ToolSpec> tools() {
        return List.of(
                new ToolSpec("list_feature_files", "List feature files",
                        "All Cucumber feature files with their feature name, tags and scenario counts, plus any Gherkin parse errors.",
                        Role.VIEWER, true, Schema.object(), (p, a) -> ToolResult.of(Map.of(
                                "featuresRoot", "src/test/resources/features",
                                "features", features.features(),
                                "parseErrors", features.problems()))),
                new ToolSpec("list_scenarios", "List scenarios",
                        "Scenarios with their ids, tags and number of test cases. Filter by tag expression and/or feature file path.",
                        Role.VIEWER, true,
                        Schema.object().string("tag_expression", "Cucumber tag expression, e.g. '@api and @smoke'", false, 200)
                                .string("feature", "Feature file path (relative to features/) or part of it", false, 200)
                                .integer("limit", "Maximum rows", 200, 1, 500),
                        (p, a) -> listScenarios(a)),
                new ToolSpec("search_feature_files", "Search feature files",
                        "Case-insensitive text search across feature names, scenario names, steps, example values and tags.",
                        Role.VIEWER, true,
                        Schema.object().string("query", "Text to find", true, 200).integer("limit", "Maximum matches", 50, 1, 200),
                        (p, a) -> search(a)),
                new ToolSpec("get_scenario_details", "Get scenario details",
                        "Full scenario: background, steps, examples and, for each step, the Java step definition it binds to "
                                + "(or 'undefined' / 'ambiguous').",
                        Role.VIEWER, true,
                        Schema.object().string("scenario_id", "Scenario id <path>:<line>", false, 300)
                                .string("name", "Exact scenario name (if no id)", false, 200),
                        (p, a) -> details(a)),
                new ToolSpec("list_cucumber_tags", "List Cucumber tags",
                        "Every tag with the number of scenarios and test cases carrying it.",
                        Role.VIEWER, true, Schema.object(), (p, a) -> tags()),
                new ToolSpec("get_step_definitions", "List step definitions",
                        "Java step definitions (expression, class, method, file:line) with how many feature steps use each, "
                                + "custom parameter types, unused definitions and undefined steps.",
                        Role.VIEWER, true,
                        Schema.object().integer("limit", "Maximum definitions", 200, 1, 500),
                        (p, a) -> stepDefinitions(a)),
                new ToolSpec("search_step_definitions", "Search step definitions",
                        "Find reusable steps. mode=text searches expressions and method names; mode=match returns the definitions "
                                + "a concrete step sentence would bind to (use it before writing a new step).",
                        Role.VIEWER, true,
                        Schema.object().string("query", "Search text, or a full step sentence for mode=match", true, 300)
                                .choice("mode", "text or match", Set.of("text", "match"), "text"),
                        (p, a) -> searchSteps(a)),
                new ToolSpec("find_duplicate_scenarios", "Find duplicate scenarios",
                        "Deterministic (non-AI) check for scenarios with identical or near-identical step sequences. "
                                + "Values in quotes and numbers are ignored when comparing. Results are candidates for review.",
                        Role.VIEWER, true,
                        Schema.object().integer("similarity_percent", "Minimum step overlap to report (50-100)", 85, 50, 100),
                        (p, a) -> duplicates(a)));
    }

    // ---------------------------------------------------------------- handlers

    private ToolResult listScenarios(Args args) {
        Expression filter = null;
        if (args.has("tag_expression")) {
            String expression = args.string("tag_expression", 200, ExecutionTools.TAG_EXPRESSION);
            ExecutionTools.validateTagExpression(expression);
            filter = TagExpressionParser.parse(expression);
        }
        String feature = args.optionalString("feature", 200, RELATIVE_PATH);
        int limit = args.integer("limit", 200, 1, 500);
        List<ScenarioInfo> selected = features.select(filter).stream()
                .filter(s -> feature == null || s.file().contains(feature))
                .toList();
        List<Map<String, Object>> rows = selected.stream().limit(limit).map(DiscoveryTools::row).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scenarios", selected.size());
        data.put("testCases", selected.stream().mapToInt(ScenarioInfo::runCount).sum());
        data.put("truncated", selected.size() > limit);
        data.put("results", rows);
        return ToolResult.of(data);
    }

    private ToolResult search(Args args) {
        String query = args.string("query", 200, Args.TEXT).toLowerCase(Locale.ROOT);
        int limit = args.integer("limit", 50, 1, 200);
        List<Map<String, Object>> matches = new ArrayList<>();
        for (ScenarioInfo s : features.scenarios()) {
            List<String[]> hits = new ArrayList<>();
            if (s.feature().toLowerCase(Locale.ROOT).contains(query)) {
                hits.add(new String[] {"feature", s.feature()});
            }
            if (s.name().toLowerCase(Locale.ROOT).contains(query)) {
                hits.add(new String[] {"scenario", s.name()});
            }
            s.tags().stream().filter(t -> t.toLowerCase(Locale.ROOT).contains(query)).forEach(t -> hits.add(new String[] {"tag", t}));
            Stream.concat(s.backgroundSteps().stream(), s.steps().stream())
                    .filter(st -> st.text().toLowerCase(Locale.ROOT).contains(query))
                    .forEach(st -> hits.add(new String[] {"step:" + st.line(), st.keyword() + " " + st.text()}));
            s.examples().forEach(e -> e.rows().stream().filter(r -> r.stream().anyMatch(c -> c.toLowerCase(Locale.ROOT).contains(query)))
                    .forEach(r -> hits.add(new String[] {"example", String.join(" | ", r)})));
            for (String[] hit : hits) {
                if (matches.size() >= limit) {
                    break;
                }
                matches.add(Map.of("scenarioId", s.id(), "scenario", s.name(), "matchedIn", hit[0], "text", hit[1]));
            }
        }
        return ToolResult.of(Map.of("query", query, "matches", matches, "truncated", matches.size() >= limit));
    }

    private ToolResult details(Args args) {
        ScenarioInfo s = resolve(args);
        Map<String, Object> data = new LinkedHashMap<>(row(s));
        data.put("rule", s.rule());
        data.put("background", bindings(s.backgroundSteps()));
        data.put("steps", bindings(s.steps()));
        data.put("examples", s.examples());
        long undefined = Stream.concat(s.backgroundSteps().stream(), s.steps().stream()).filter(st -> steps.match(st.text()).isEmpty()).count();
        data.put("undefinedSteps", undefined);
        return ToolResult.of(data);
    }

    private ScenarioInfo resolve(Args args) {
        if (args.has("scenario_id")) {
            String id = args.string("scenario_id", 300, SCENARIO_ID);
            return features.byId(id).orElseThrow(() -> ToolException.notFound("No scenario with id " + id));
        }
        if (args.has("name")) {
            String name = args.string("name", 200, Args.TEXT);
            return features.byName(name).stream().findFirst().orElseThrow(() -> ToolException.notFound("No scenario named '" + name + "'"));
        }
        throw ToolException.invalid("Provide scenario_id or name");
    }

    private List<Map<String, Object>> bindings(List<ScenarioInfo.StepInfo> stepList) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ScenarioInfo.StepInfo st : stepList) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("line", st.line());
            m.put("step", st.keyword() + " " + st.text());
            if (st.hasDataTable()) {
                m.put("dataTable", true);
            }
            if (st.hasDocString()) {
                m.put("docString", true);
            }
            if (st.text().contains("<")) {
                m.put("binding", "outline step: bound per example row");
                result.add(m);
                continue;
            }
            List<StepDefinitionIndex.Match> matches = steps.match(st.text());
            m.put("binding", matches.isEmpty() ? "undefined" : matches.size() > 1 ? "ambiguous" : "matched");
            m.put("definitions", matches.stream().map(x -> Map.of(
                    "expression", x.definition().expression(),
                    "method", x.definition().className() + "." + x.definition().method(),
                    "location", x.definition().file() + ":" + x.definition().line(),
                    "arguments", x.arguments())).toList());
            result.add(m);
        }
        return result;
    }

    private ToolResult tags() {
        Map<String, int[]> counts = new TreeMap<>();
        for (ScenarioInfo s : features.scenarios()) {
            Set<String> all = new HashSet<>(s.tags());
            s.examples().forEach(e -> all.addAll(e.tags()));
            for (String tag : all) {
                int[] c = counts.computeIfAbsent(tag, k -> new int[2]);
                c[0]++;
                c[1] += (int) s.tagSetsPerTestCase().stream().filter(set -> set.contains(tag)).count();
            }
        }
        List<Map<String, Object>> rows = counts.entrySet().stream()
                .map(e -> Map.<String, Object>of("tag", e.getKey(), "scenarios", e.getValue()[0], "testCases", e.getValue()[1]))
                .toList();
        return ToolResult.of(Map.of("tags", rows, "note", "@quarantine is excluded by every runner; @noretry is never retried"));
    }

    private ToolResult stepDefinitions(Args args) {
        int limit = args.integer("limit", 200, 1, 500);
        Map<StepDefinitionIndex.StepDefinition, Integer> usage = new LinkedHashMap<>();
        steps.definitions().forEach(d -> usage.put(d, 0));
        List<Map<String, Object>> undefined = new ArrayList<>();
        List<Map<String, Object>> ambiguous = new ArrayList<>();
        for (ScenarioInfo s : features.scenarios()) {
            for (ScenarioInfo.StepInfo st : Stream.concat(s.backgroundSteps().stream(), s.steps().stream()).toList()) {
                for (String text : s.concreteTexts(st)) {
                    List<StepDefinitionIndex.Match> m = steps.match(text);
                    m.forEach(x -> usage.merge(x.definition(), 1, Integer::sum));
                    if (m.isEmpty()) {
                        undefined.add(Map.of("scenarioId", s.id(), "line", st.line(), "step", text));
                    } else if (m.size() > 1) {
                        ambiguous.add(Map.of("scenarioId", s.id(), "line", st.line(), "step", text, "definitions", m.size()));
                    }
                }
            }
        }
        List<Map<String, Object>> defs = usage.entrySet().stream().limit(limit).map(e -> {
            StepDefinitionIndex.StepDefinition d = e.getKey();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("keyword", d.keyword());
            m.put("expression", d.expression());
            m.put("method", d.className() + "." + d.method());
            m.put("location", d.file() + ":" + d.line());
            m.put("usages", e.getValue());
            return m;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("definitions", usage.size());
        data.put("results", defs);
        data.put("parameterTypes", steps.parameterTypes());
        data.put("unused", usage.entrySet().stream().filter(e -> e.getValue() == 0)
                .map(e -> e.getKey().expression() + " (" + e.getKey().file() + ":" + e.getKey().line() + ")").toList());
        data.put("undefinedSteps", undefined);
        data.put("ambiguousSteps", ambiguous);
        data.put("indexProblems", steps.problems());
        return ToolResult.of(data);
    }

    private ToolResult searchSteps(Args args) {
        String mode = args.choice("mode", Set.of("text", "match"), "text");
        String query = args.string("query", 300, Args.TEXT);
        List<Map<String, Object>> results = new ArrayList<>();
        if ("match".equals(mode)) {
            for (StepDefinitionIndex.Match m : steps.match(query)) {
                results.add(Map.of("expression", m.definition().expression(), "method", m.definition().className() + "." + m.definition().method(),
                        "location", m.definition().file() + ":" + m.definition().line(), "arguments", m.arguments()));
            }
        } else {
            String q = query.toLowerCase(Locale.ROOT);
            for (StepDefinitionIndex.StepDefinition d : steps.definitions()) {
                if (d.expression().toLowerCase(Locale.ROOT).contains(q) || d.method().toLowerCase(Locale.ROOT).contains(q)) {
                    results.add(Map.of("keyword", d.keyword(), "expression", d.expression(), "method", d.className() + "." + d.method(),
                            "location", d.file() + ":" + d.line()));
                }
            }
        }
        return ToolResult.of(Map.of("mode", mode, "query", query, "results", results,
                "verdict", "match".equals(mode) ? results.isEmpty() ? "undefined: a new step definition is needed"
                        : results.size() > 1 ? "ambiguous" : "reuse the existing step" : results.size() + " found"));
    }

    private ToolResult duplicates(Args args) {
        double threshold = args.integer("similarity_percent", 85, 50, 100) / 100.0;
        List<ScenarioInfo> all = features.scenarios();
        Map<ScenarioInfo, List<String>> normalized = new HashMap<>();
        all.forEach(s -> normalized.put(s, s.normalizedSteps()));
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                List<String> a = normalized.get(all.get(i));
                List<String> b = normalized.get(all.get(j));
                double score = a.equals(b) ? 1.0 : jaccard(a, b);
                if (score >= threshold) {
                    pairs.add(Map.of(
                            "similarityPercent", Math.round(score * 100),
                            "kind", a.equals(b) ? "identical steps" : "similar steps",
                            "first", all.get(i).id() + " " + all.get(i).name(),
                            "second", all.get(j).id() + " " + all.get(j).name()));
                }
            }
        }
        pairs.sort((x, y) -> Long.compare((Long) y.get("similarityPercent"), (Long) x.get("similarityPercent")));
        return ToolResult.of(Map.of("thresholdPercent", Math.round(threshold * 100), "scenariosCompared", all.size(), "candidates", pairs,
                "method", "Steps compared after removing quoted values and numbers; Jaccard overlap of step sets. Heuristic: review before deleting."));
    }

    public static double jaccard(List<String> a, List<String> b) {
        Set<String> sa = new HashSet<>(a);
        Set<String> sb = new HashSet<>(b);
        if (sa.isEmpty() && sb.isEmpty()) {
            return 1.0;
        }
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return (double) inter.size() / union.size();
    }

    static Map<String, Object> row(ScenarioInfo s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("name", s.name());
        m.put("keyword", s.keyword());
        m.put("feature", s.feature());
        m.put("tags", s.tags());
        m.put("testCases", s.runCount());
        return m;
    }
}
