package io.qaplatform.ai.planning;

import io.cucumber.tagexpressions.TagExpressionParser;
import io.qaplatform.ai.llm.AiClient;
import io.qaplatform.ai.llm.LlmException;
import io.qaplatform.ai.llm.PromptManager;
import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.discovery.FeatureIndex;
import io.qaplatform.mcp.discovery.ScenarioInfo;
import io.qaplatform.mcp.execution.ExecutionRecord;
import io.qaplatform.mcp.execution.ExecutionRequest;
import io.qaplatform.mcp.execution.ExecutionService;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.tools.ExecutionTools;
import io.qaplatform.mcp.tools.ToolException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Natural-language test requests → validated execution plans.
 * <ol>
 *   <li>Interpret: a model (if configured) and deterministic keyword rules both read the request.</li>
 *   <li>Discover and validate: tags must exist, the environment must be allowed, the selection must
 *       match at least one scenario. An AI interpretation that fails validation is discarded.</li>
 *   <li>Explain: test cases selected vs the full regression, approval needs, how to run everything.</li>
 *   <li>Execute: only via execute_test_plan, through the normal execution service (roles, approvals,
 *       timeouts, audit all apply).</li>
 * </ol>
 */
public final class TestPlanner {

    public static final Pattern PLAN_ID = Pattern.compile("plan-\\d{8}-\\d{6}-[0-9a-f]{6}");
    private static final Duration PLAN_TTL = Duration.ofHours(24);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("payments?|checkout|card", "@payment"),
            Map.entry("users?|user management|profiles?|customers?", "@user"),
            Map.entry("log ?in|sign ?in|sign ?out|log ?out|authentication|auth", "@login"),
            Map.entry("end[- ]to[- ]end|e2e|hybrid|journeys?", "@e2e"),
            Map.entry("ui|browser|front[- ]?end|web", "@ui"),
            Map.entry("api|rest|backend|back[- ]end", "@api"),
            Map.entry("smoke", "@smoke"),
            Map.entry("sanity", "@sanity"),
            Map.entry("critical", "@critical"),
            Map.entry("negative|error cases?|failures?|invalid", "@negative"),
            Map.entry("positive|happy[- ]path", "@positive"));
    private static final List<Set<String>> GROUPS = List.of(
            Set.of("@smoke", "@regression", "@sanity", "@critical"),
            Set.of("@ui", "@api", "@e2e"),
            Set.of("@positive", "@negative"));

    private final McpConfig config;
    private final FeatureIndex features;
    private final ExecutionService executions;
    private final AiClient ai;
    private final Path dir;
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final SecureRandom random = new SecureRandom();

    public TestPlanner(McpConfig config, FeatureIndex features, ExecutionService executions, AiClient ai) {
        this.config = config;
        this.features = features;
        this.executions = executions;
        this.ai = ai;
        this.dir = config.stateDir().resolve("plans");
    }

    // ------------------------------------------------------------------ planning

    public Map<String, Object> plan(Principal requester, String request, boolean useAi) {
        Interpretation rules = interpretWithRules(request);
        Interpretation chosen = rules;
        String note = "Interpreted with deterministic keyword rules.";
        Map<String, Object> aiView = null;
        if (useAi && ai.enabled()) {
            try {
                Interpretation fromAi = interpretWithAi(request);
                String problem = problem(fromAi);
                aiView = new LinkedHashMap<>(fromAi.asMap());
                if (problem == null) {
                    chosen = fromAi;
                    note = "Interpreted by " + ai.provider().name() + "/" + ai.provider().model() + " and validated against the project.";
                } else {
                    aiView.put("rejected", problem);
                    note = "The model's interpretation was rejected (" + problem + "); used keyword rules instead.";
                }
            } catch (LlmException e) {
                note = "AI unavailable (" + e.kind() + "); used keyword rules.";
            }
        } else if (useAi) {
            note = "AI not configured; used keyword rules.";
        }
        if (chosen.clarification() != null && !chosen.fullRegression() && chosen.tagExpression() == null && chosen.scenarioNames().isEmpty()) {
            return Map.of("status", "NEEDS_CLARIFICATION", "question", chosen.clarification(), "note", note);
        }
        String problem = problem(chosen);
        if (problem != null) {
            throw ToolException.invalid("Could not build a valid plan: " + problem + ". Available tags: " + knownTags().keySet());
        }
        TestPlan plan = save(chosen, request, requester);
        Map<String, Object> result = new LinkedHashMap<>(describe(plan));
        result.put("interpretationNote", note);
        if (aiView != null) {
            result.put("aiInterpretation", aiView);
        }
        result.put("rulesInterpretation", rules.asMap());
        return result;
    }

    record Interpretation(String source, boolean fullRegression, String tagExpression, List<String> scenarioNames, String environment,
                          String browser, String clarification, String explanation) {
        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", source);
            m.put("fullRegression", fullRegression);
            m.put("tagExpression", tagExpression);
            m.put("scenarioNames", scenarioNames);
            m.put("environment", environment);
            m.put("browser", browser);
            m.put("explanation", explanation);
            return m;
        }
    }

    Interpretation interpretWithRules(String request) {
        String text = " " + request.toLowerCase(Locale.ROOT) + " ";
        if (text.matches("(?s).*\\b(prod|production|live)\\b.*")) {
            return new Interpretation("rules", false, null, List.of(), null, null,
                    "Production is not an allowed test environment. Which of " + config.allowedEnvironments() + " should be used?", null);
        }
        String env = firstMatch(text, "\\b(local|qa|staging)\\b", "local");
        String browser = switch (firstMatch(text, "\\b(chromium|chrome|firefox|webkit|safari|edge|msedge)\\b", "chromium")) {
            case "safari" -> "webkit";
            case "edge" -> "msedge";
            case String b -> b;
        };
        List<String> names = new ArrayList<>();
        Matcher quoted = Pattern.compile("\"([^\"]{3,200})\"").matcher(request);
        while (quoted.find()) {
            features.byName(quoted.group(1)).stream().findFirst().ifPresent(s -> names.add(s.name()));
        }
        if (!names.isEmpty()) {
            return new Interpretation("rules", false, null, names, env, browser, null, "Named scenario(s) in quotes");
        }
        if (text.matches("(?s).*\\b(full|complete|entire|whole) (regression|suite)\\b.*|.*\\ball (the )?(tests|scenarios)\\b.*|.*\\beverything\\b.*")) {
            return new Interpretation("rules", true, null, List.of(), env, browser, null, "Full regression requested");
        }
        Set<String> tags = new LinkedHashSet<>();
        Map<String, Integer> known = knownTags();
        for (String tag : known.keySet()) {
            String word = tag.substring(1);
            if (Pattern.compile("\\b" + Pattern.quote(word) + "s?\\b").matcher(text).find()) {
                tags.add(tag);
            }
        }
        SYNONYMS.forEach((regex, tag) -> {
            if (known.containsKey(tag) && Pattern.compile("\\b(" + regex + ")\\b").matcher(text).find()) {
                tags.add(tag);
            }
        });
        if (tags.isEmpty()) {
            return new Interpretation("rules", false, null, List.of(), env, browser,
                    "Which scenarios should run? Name a tag (" + known.keySet() + "), a scenario in quotes, or say 'full regression'.", null);
        }
        // Same group (e.g. @ui, @api) → OR; different groups → AND; domain tags (@payment, @user...) form their own group.
        Map<Integer, List<String>> grouped = new TreeMap<>();
        for (String t : tags) {
            int group = 3;
            for (int i = 0; i < GROUPS.size(); i++) {
                if (GROUPS.get(i).contains(t)) {
                    group = i;
                }
            }
            grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(t);
        }
        String expression = grouped.values().stream()
                .map(g -> g.size() == 1 ? g.get(0) : "(" + String.join(" or ", g) + ")")
                .collect(Collectors.joining(" and "));
        return new Interpretation("rules", false, expression, List.of(), env, browser, null, "Matched words to tags " + tags);
    }

    Interpretation interpretWithAi(String request) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("tags", knownTags().entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + " test cases)").collect(Collectors.joining(", ")));
        values.put("environments", String.join(", ", config.allowedEnvironments()));
        values.put("browsers", String.join(", ", ExecutionTools.BROWSERS));
        values.put("scenarios", features.scenarios().stream().map(s -> "- " + s.name() + " " + s.tags()).collect(Collectors.joining("\n")));
        values.put("request", PromptManager.untrusted("user request", request, 2000));
        JsonNode j = ai.json("test-planning", values).json();
        List<String> names = new ArrayList<>();
        j.path("scenario_names").forEach(n -> names.add(n.asString("")));
        String tagExpression = j.path("tag_expression").isNull() ? null : j.path("tag_expression").asString(null);
        return new Interpretation("ai", j.path("full_regression").asBoolean(false), blankToNull(tagExpression), names,
                blankToNull(j.path("environment").asString(null)), blankToNull(j.path("browser").asString(null)),
                blankToNull(j.path("clarification_question").asString(null)), j.path("explanation").asString(""));
    }

    /** Null when the interpretation is runnable; otherwise why not. */
    String problem(Interpretation i) {
        if (i.environment() == null || !config.allowedEnvironments().contains(i.environment())) {
            return "environment '" + i.environment() + "' is not one of " + config.allowedEnvironments();
        }
        if (i.browser() == null || !ExecutionTools.BROWSERS.contains(i.browser())) {
            return "browser '" + i.browser() + "' is not one of " + ExecutionTools.BROWSERS;
        }
        if (i.fullRegression()) {
            return null;
        }
        if (!i.scenarioNames().isEmpty()) {
            for (String n : i.scenarioNames()) {
                if (features.byName(n).isEmpty()) {
                    return "no scenario named '" + n + "'";
                }
            }
            return null;
        }
        if (i.tagExpression() == null) {
            return "no selection (tags, scenario names or full regression)";
        }
        try {
            if (!ExecutionTools.TAG_EXPRESSION.matcher(i.tagExpression()).matches()) {
                return "tag expression has invalid characters";
            }
            ExecutionTools.validateTagExpression(i.tagExpression());
        } catch (ToolException e) {
            return e.getMessage();
        }
        Matcher m = Pattern.compile("@[A-Za-z0-9_-]+").matcher(i.tagExpression());
        while (m.find()) {
            if (!knownTags().containsKey(m.group())) {
                return "unknown tag " + m.group();
            }
        }
        if (count("(" + i.tagExpression() + ") and not @quarantine") == 0) {
            return "'" + i.tagExpression() + "' matches no scenario";
        }
        return null;
    }

    // ------------------------------------------------------------------ execution

    public Map<String, Object> execute(Principal requester, String planId) {
        TestPlan plan = load(planId);
        if (plan.executionId() != null) {
            throw ToolException.conflict("Plan " + planId + " was already executed as " + plan.executionId());
        }
        if (plan.createdAt().plus(PLAN_TTL).isBefore(Instant.now())) {
            throw ToolException.conflict("Plan " + planId + " expired; create a new one");
        }
        Interpretation again = new Interpretation(plan.source(), plan.fullRegression(), plan.tagExpression(), plan.scenarioNames(),
                plan.environment(), plan.browser(), null, plan.explanation());
        String problem = problem(again);
        if (problem != null) {
            throw ToolException.conflict("Plan is no longer valid: " + problem);
        }
        ExecutionRequest request = new ExecutionRequest("plan", "testng-regression.xml",
                plan.fullRegression() || plan.tagExpression() == null ? null : "(" + plan.tagExpression() + ") and not @quarantine",
                plan.scenarioNames().isEmpty() ? null : plan.scenarioNames(), plan.environment(), plan.browser(), 4, 0);
        ExecutionRecord record = executions.submit(request, requester);
        persist(plan.executed(record.id()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("executionId", record.id());
        result.put("status", record.status().name());
        result.put("message", record.message());
        result.put("next", "Follow with get_execution_status execution_id=" + record.id() + " wait_seconds=60, then summarize_execution.");
        return result;
    }

    // ------------------------------------------------------------------ helpers

    Map<String, Object> describe(TestPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", plan.planId());
        m.put("status", "READY (nothing runs until execute_test_plan is called)");
        m.put("request", plan.request());
        m.put("selection", plan.fullRegression() ? "FULL REGRESSION (every non-quarantined scenario)"
                : plan.tagExpression() != null ? "tags: (" + plan.tagExpression() + ") and not @quarantine" : "scenarios: " + plan.scenarioNames());
        m.put("environment", plan.environment());
        m.put("browser", plan.browser());
        m.put("testCases", plan.testCases());
        m.put("coverage", plan.testCases() + " of " + plan.regressionTestCases() + " regression test cases");
        if (!plan.fullRegression()) {
            m.put("notIncluded", (plan.regressionTestCases() - plan.testCases()) + " regression test cases are NOT in this plan. "
                    + "Run the complete suite with run_regression_tests at any time.");
        }
        m.put("approvalRequired", plan.approvalRequired()
                ? "yes: '" + plan.environment() + "' runs wait for an admin other than the requester" : "no");
        m.put("scenarios", selected(plan).stream().limit(25).map(s -> s.id() + " " + s.name()).toList());
        m.put("explanation", plan.explanation());
        return m;
    }

    private List<ScenarioInfo> selected(TestPlan plan) {
        if (!plan.scenarioNames().isEmpty()) {
            return features.scenarios().stream().filter(s -> plan.scenarioNames().contains(s.name())).toList();
        }
        String expression = plan.fullRegression() ? "not @quarantine" : "(" + plan.tagExpression() + ") and not @quarantine";
        return features.select(TagExpressionParser.parse(expression));
    }

    private TestPlan save(Interpretation i, String request, Principal requester) {
        int regression = count("not @quarantine");
        int cases;
        if (i.fullRegression()) {
            cases = regression;
        } else if (!i.scenarioNames().isEmpty()) {
            cases = features.scenarios().stream().filter(s -> i.scenarioNames().contains(s.name())).mapToInt(ScenarioInfo::runCount).sum();
        } else {
            cases = count("(" + i.tagExpression() + ") and not @quarantine");
        }
        byte[] b = new byte[3];
        random.nextBytes(b);
        String id = "plan-" + TIME.format(Instant.now()) + "-" + HexFormat.of().formatHex(b);
        TestPlan plan = new TestPlan(id, request, i.source(), i.fullRegression(), i.fullRegression() ? null : i.tagExpression(),
                i.scenarioNames(), i.environment(), i.browser(), cases, regression, config.requiresApproval(i.environment()),
                i.explanation(), requester.name(), Instant.now(), null);
        persist(plan);
        return plan;
    }

    public TestPlan load(String planId) {
        if (!PLAN_ID.matcher(planId).matches()) {
            throw ToolException.invalid("plan_id must look like plan-20261119-101500-a1b2c3");
        }
        Path file = dir.resolve(planId + ".json");
        if (!Files.isRegularFile(file)) {
            throw ToolException.notFound("No plan " + planId);
        }
        return json.readValue(file.toFile(), TestPlan.class);
    }

    /** Stores a plan built elsewhere (e.g. regression recommendations) so it can be executed by id. */
    public Map<String, Object> savePlan(Principal requester, String request, String tagExpression, List<String> scenarioNames,
                                        boolean fullRegression, String environment, String browser, String explanation) {
        Interpretation i = new Interpretation("recommendation", fullRegression, tagExpression, scenarioNames == null ? List.of() : scenarioNames,
                environment, browser, null, explanation);
        String problem = problem(i);
        if (problem != null) {
            throw ToolException.invalid(problem);
        }
        return describe(save(i, request, requester));
    }

    private void persist(TestPlan plan) {
        try {
            Files.createDirectories(dir);
            json.writeValue(dir.resolve(plan.planId() + ".json").toFile(), plan);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Integer> knownTags() {
        Map<String, Integer> tags = new TreeMap<>();
        for (ScenarioInfo s : features.scenarios()) {
            for (List<String> set : s.tagSetsPerTestCase()) {
                set.forEach(t -> tags.merge(t, 0, Integer::sum));
            }
        }
        tags.replaceAll((t, v) -> count(t));
        return tags;
    }

    private int count(String expression) {
        return features.select(TagExpressionParser.parse(expression)).stream().mapToInt(ScenarioInfo::runCount).sum();
    }

    private static String firstMatch(String text, String regex, String fallback) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : fallback;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() || "null".equals(s) ? null : s.trim();
    }
}
