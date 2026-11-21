package io.qaplatform.ai.recommend;

import io.qaplatform.mcp.util.PathNames;
import io.cucumber.tagexpressions.TagExpressionParser;
import io.qaplatform.ai.llm.AiClient;
import io.qaplatform.ai.llm.LlmException;
import io.qaplatform.ai.llm.PromptManager;
import io.qaplatform.ai.planning.TestPlanner;
import io.qaplatform.mcp.discovery.FeatureIndex;
import io.qaplatform.mcp.discovery.ScenarioInfo;
import io.qaplatform.mcp.discovery.StepDefinitionIndex;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.tools.ExecutionTools;
import io.qaplatform.mcp.tools.ToolException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Change-based regression selection. Changed files are traced deterministically to the scenarios
 * that exercise them (feature → scenarios; step class → scenarios using its steps; page object /
 * service → step classes that use it, one level of indirection; framework-wide files → full
 * regression). A model may suggest additional tags from the change description, which are
 * validated and kept separate. The complete regression is always offered.
 */
public final class RegressionAdvisor {

    private static final Pattern FRAMEWORK_WIDE = Pattern.compile(
            "pom\\.xml|testng-[a-z-]+\\.xml|config/.*|src/main/java/io/qaplatform/(core|ui/browser|ui/network|ui/utils|api/client|api/auth|api/responses|"
                    + "api/schemas|api/assertions|api/requests)/.*|src/test/java/io/qaplatform/bdd/(hooks|context|runners|support)/.*|"
                    + "src/test/resources/(cucumber|allure)\\.properties|src/test/resources/logback-test\\.xml|src/test/resources/config/.*|sut/app\\.py|"
                    + "sut/requirements\\.txt|Dockerfile|docker-compose\\.yml");
    private static final Pattern NO_TEST_IMPACT = Pattern.compile("docs/.*|.*\\.md|\\.github/.*|scripts/.*|mcp-server/.*|\\.gitignore|\\.dockerignore");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_./-]{1,300}");

    private final Path projectDir;
    private final FeatureIndex features;
    private final StepDefinitionIndex steps;
    private final TestPlanner planner;
    private final AiClient ai;
    private io.qaplatform.ai.insights.ExecutionHistory history;
    private io.qaplatform.ai.rag.RagClient rag = new io.qaplatform.ai.rag.RagClient(null, null);

    public RegressionAdvisor(Path projectDir, FeatureIndex features, StepDefinitionIndex steps, TestPlanner planner, AiClient ai) {
        this.projectDir = projectDir;
        this.features = features;
        this.steps = steps;
        this.planner = planner;
        this.ai = ai;
    }

    /** Optional inputs for the full selection workflow: run history and the knowledge base. */
    public RegressionAdvisor withHistory(io.qaplatform.ai.insights.ExecutionHistory runHistory, io.qaplatform.ai.rag.RagClient knowledge) {
        this.history = runHistory;
        this.rag = knowledge;
        return this;
    }

    /** Recently failed or flaky scenarios in the same domains as the impacted ones (deterministic, from run history). */
    private List<ScenarioInfo> historyAdditions(Set<ScenarioInfo> impacted) {
        if (history == null || impacted.isEmpty()) {
            return List.of();
        }
        Set<String> domains = new LinkedHashSet<>();
        impacted.forEach(s -> s.tags().stream().filter(t -> !Set.of("@smoke", "@regression", "@sanity", "@critical", "@ui", "@api", "@e2e",
                "@positive", "@negative", "@quarantine", "@noretry").contains(t)).forEach(domains::add));
        Set<String> recentProblems = new LinkedHashSet<>();
        history.outcomes(10).stream().filter(o -> "failed".equals(o.status()) || "flaky".equals(o.status()))
                .forEach(o -> recentProblems.add(o.scenario()));
        return features.scenarios().stream()
                .filter(s -> !impacted.contains(s) && recentProblems.contains(s.name()) && !s.tags().contains("@quarantine"))
                .filter(s -> s.tags().stream().anyMatch(domains::contains))
                .toList();
    }

    private Map<String, Object> knowledgeSuggestions(String description, Set<ScenarioInfo> impacted) {
        try {
            tools.jackson.databind.JsonNode hits = rag.search(description.length() > 900 ? description.substring(0, 900) : description, 10,
                    Map.of("document_type", "feature"), "hybrid");
            Set<String> selected = new LinkedHashSet<>();
            impacted.forEach(s -> selected.add(s.name()));
            List<String> related = new ArrayList<>();
            hits.path("results").forEach(h -> {
                String scenario = h.path("metadata").path("scenario").asString("");
                if (!scenario.isEmpty() && !selected.contains(scenario) && !related.contains(scenario)) {
                    related.add(scenario + "  " + h.path("citation").asString(""));
                }
            });
            return Map.of("status", "RELATED SCENARIOS FROM THE KNOWLEDGE BASE (not in the plan)", "scenarios", related);
        } catch (ToolException e) {
            return Map.of("available", false, "reason", e.getMessage());
        }
    }

    public Map<String, Object> suggest(Principal requester, List<String> changedFiles, String description, String environment,
                                       String browser, boolean useAi) {
        Set<ScenarioInfo> impacted = new LinkedHashSet<>();
        List<Map<String, Object>> reasons = new ArrayList<>();
        List<String> noImpact = new ArrayList<>();
        boolean full = false;
        for (String raw : changedFiles) {
            String file = raw.strip().replace('\\', '/');
            if (!PATH.matcher(file).matches() || file.contains("..")) {
                throw ToolException.invalid("Invalid changed file path: " + raw);
            }
            if (NO_TEST_IMPACT.matcher(file).matches()) {
                noImpact.add(file);
                continue;
            }
            if (FRAMEWORK_WIDE.matcher(file).matches()) {
                full = true;
                reasons.add(Map.of("file", file, "impact", "framework-wide or application-wide change: full regression"));
                continue;
            }
            Set<ScenarioInfo> hit = impactOf(file);
            if (hit == null) {
                full = true;
                reasons.add(Map.of("file", file, "impact", "could not be traced to specific scenarios: full regression to be safe"));
            } else {
                impacted.addAll(hit);
                reasons.add(Map.of("file", file, "impact", hit.size() + " scenario(s)", "scenarios",
                        hit.stream().map(s -> s.id() + " " + s.name()).limit(20).toList()));
            }
        }
        impacted.removeIf(s -> s.tags().contains("@quarantine"));
        List<ScenarioInfo> fromHistory = full ? List.of() : historyAdditions(impacted);
        if (!fromHistory.isEmpty()) {
            reasons.add(Map.of("file", "(run history)", "impact", fromHistory.size() + " scenario(s) in the same area failed or were flaky in recent runs",
                    "scenarios", fromHistory.stream().map(s -> s.id() + " " + s.name()).toList()));
            impacted.addAll(fromHistory);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changedFiles", changedFiles.size());
        result.put("analysis", reasons);
        result.put("filesWithoutTestImpact", noImpact);
        int regression = features.select(TagExpressionParser.parse("not @quarantine")).stream().mapToInt(ScenarioInfo::runCount).sum();
        String explanation;
        Map<String, Object> plan;
        if (full || (!impacted.isEmpty() && impacted.stream().mapToInt(ScenarioInfo::runCount).sum() >= regression)) {
            explanation = "Change affects shared code: run the full regression.";
            plan = planner.savePlan(requester, "regression for changes: " + changedFiles, null, null, true, environment, browser, explanation);
            result.put("recommendation", "FULL REGRESSION");
        } else if (impacted.isEmpty()) {
            explanation = "No test-relevant change found.";
            plan = null;
            result.put("recommendation", "NO TESTS AFFECTED (consider smoke anyway)");
        } else {
            explanation = "Scenarios that exercise the changed code.";
            List<String> names = impacted.stream().map(ScenarioInfo::name).distinct().toList();
            plan = planner.savePlan(requester, "regression for changes: " + changedFiles, null, names, false, environment, browser, explanation);
            result.put("recommendation", "TARGETED: " + impacted.stream().mapToInt(ScenarioInfo::runCount).sum() + " of " + regression + " test cases");
        }
        result.put("plan", plan);
        result.put("fullRegressionOption", "run_regression_tests runs every non-quarantined scenario (" + regression + " test cases); use it before releases.");

        if (rag.configured() && description != null && !description.isBlank()) {
            result.put("knowledgeBase", knowledgeSuggestions(description, impacted));
        }
        if (useAi && ai.enabled() && description != null && !description.isBlank()) {
            result.put("aiSuggestion", aiSuggestion(description, changedFiles, impacted));
        } else if (useAi && description != null && !description.isBlank()) {
            result.put("aiSuggestion", Map.of("available", false, "reason", "AI provider not configured"));
        }
        return result;
    }

    /** Scenarios affected by one file, or null if it cannot be traced. */
    Set<ScenarioInfo> impactOf(String file) {
        if (file.startsWith("src/test/resources/features/") && file.endsWith(".feature")) {
            String rel = file.substring("src/test/resources/features/".length());
            return features.scenarios().stream().filter(s -> s.file().equals(rel)).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        String simpleName = PathNames.name(Path.of(file)).replaceFirst("\\.[a-z]+$", "");
        if (file.startsWith("src/test/java/io/qaplatform/bdd/steps/")) {
            return scenariosUsingStepClasses(Set.of(simpleName));
        }
        if (file.startsWith("src/main/java/") && file.endsWith(".java")) {
            Set<String> names = new LinkedHashSet<>(List.of(simpleName));
            // one level of indirection: main classes that use the changed class (component → page)
            javaFiles(projectDir.resolve("src/main/java")).stream()
                    .filter(p -> mentions(p, simpleName))
                    .forEach(p -> names.add(PathNames.name(p).replace(".java", "")));
            return scenariosUsingStepClasses(stepClassesMentioning(names));
        }
        if (file.startsWith("src/test/resources/testdata/") || file.startsWith("src/test/resources/schemas/")) {
            String fileName = PathNames.name(Path.of(file));
            Set<String> users = new LinkedHashSet<>();
            Stream.concat(javaFiles(projectDir.resolve("src/main/java")).stream(), javaFiles(projectDir.resolve("src/test/java")).stream())
                    .filter(p -> mentions(p, fileName.replace(".json", "")))
                    .forEach(p -> users.add(PathNames.name(p).replace(".java", "")));
            if (users.isEmpty()) {
                return null;
            }
            Set<String> withIndirect = new LinkedHashSet<>(users);
            javaFiles(projectDir.resolve("src/main/java")).stream()
                    .filter(p -> users.stream().anyMatch(u -> mentions(p, u)))
                    .forEach(p -> withIndirect.add(PathNames.name(p).replace(".java", "")));
            Set<String> stepClasses = stepClassesMentioning(withIndirect);
            withIndirect.stream().filter(n -> n.endsWith("Steps")).forEach(stepClasses::add);
            return scenariosUsingStepClasses(stepClasses);
        }
        if ("sut/ui.py".equals(file)) {
            return new LinkedHashSet<>(features.select(TagExpressionParser.parse("@ui or @e2e")));
        }
        return null;
    }

    private Set<String> stepClassesMentioning(Set<String> classNames) {
        return javaFiles(projectDir.resolve("src/test/java/io/qaplatform/bdd/steps")).stream()
                .filter(p -> classNames.stream().anyMatch(n -> mentions(p, n)))
                .map(p -> PathNames.name(p).replace(".java", ""))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<ScenarioInfo> scenariosUsingStepClasses(Set<String> stepClasses) {
        Set<ScenarioInfo> result = new LinkedHashSet<>();
        for (ScenarioInfo s : features.scenarios()) {
            boolean uses = Stream.concat(s.backgroundSteps().stream(), s.steps().stream())
                    .flatMap(st -> s.concreteTexts(st).stream())
                    .flatMap(text -> steps.match(text).stream())
                    .anyMatch(m -> stepClasses.contains(m.definition().className()));
            if (uses) {
                result.add(s);
            }
        }
        return result;
    }

    private Map<String, Object> aiSuggestion(String description, List<String> changedFiles, Set<ScenarioInfo> impacted) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("tags", String.join(", ", features.scenarios().stream().flatMap(s -> s.tags().stream()).distinct().sorted().toList()));
        values.put("files", PromptManager.untrusted("changed files", String.join("\n", changedFiles), 4000));
        values.put("description", PromptManager.untrusted("change description", description, 4000));
        values.put("impacted", impacted.stream().map(s -> "- " + s.name() + " " + s.tags()).collect(Collectors.joining("\n")));
        try {
            JsonNode j = ai.json("regression-advice", values).json();
            List<String> valid = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            j.path("additional_tag_expressions").forEach(t -> {
                String expr = t.asString("");
                try {
                    if (!ExecutionTools.TAG_EXPRESSION.matcher(expr).matches()) {
                        throw ToolException.invalid("bad characters");
                    }
                    ExecutionTools.validateTagExpression(expr);
                    boolean matches = !features.select(TagExpressionParser.parse("(" + expr + ") and not @quarantine")).isEmpty();
                    (matches ? valid : rejected).add(expr);
                } catch (ToolException e) {
                    rejected.add(expr);
                }
            });
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "SUGGESTION (not included in the plan; run with run_tests_by_tag if you agree)");
            m.put("riskLevel", j.path("risk_level").asString("unknown"));
            m.put("additionalTagExpressions", valid);
            m.put("rejectedSuggestions", rejected);
            m.put("rationale", j.path("rationale").asString(""));
            return m;
        } catch (LlmException e) {
            return Map.of("available", false, "reason", e.kind() + ": " + e.getMessage());
        }
    }

    private static boolean mentions(Path file, String word) {
        try {
            return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(Files.readString(file, StandardCharsets.UTF_8)).find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
