package io.qaplatform.ai.generation;

import io.qaplatform.mcp.discovery.FeatureIndex;
import io.qaplatform.mcp.discovery.ScenarioInfo;
import io.qaplatform.mcp.discovery.StepDefinitionIndex;
import io.qaplatform.mcp.security.Redactor;
import io.qaplatform.mcp.tools.DiscoveryTools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Deterministic checks a feature file must pass before a human is asked to accept it (and that
 * hand-written features can use too):
 * <ol>
 *   <li>Gherkin syntax (Cucumber's parser)</li>
 *   <li>every step binds to exactly one step definition (Cucumber's expression engine), or is listed as undefined</li>
 *   <li>no duplicate of an existing scenario (same name, or same steps ignoring values)</li>
 *   <li>tag conventions: one layer tag, one scope tag, lower-case, known tags; no @quarantine on new work</li>
 *   <li>no literal credentials or card numbers (features name roles, never secrets)</li>
 * </ol>
 */
public final class GherkinValidator {

    public static final Set<String> LAYER_TAGS = Set.of("@ui", "@api", "@e2e");
    public static final Set<String> SCOPE_TAGS = Set.of("@smoke", "@regression", "@sanity");
    public static final Set<String> CONVENTION_TAGS = Set.of("@smoke", "@regression", "@sanity", "@critical", "@ui", "@api", "@e2e",
            "@login", "@payment", "@user", "@positive", "@negative", "@quarantine", "@noretry");

    public record Report(boolean valid, boolean readyToRun, Map<String, Object> details) {
    }

    private final FeatureIndex features;
    private final StepDefinitionIndex steps;

    public GherkinValidator(FeatureIndex features, StepDefinitionIndex steps) {
        this.features = features;
        this.steps = steps;
    }

    public Report validate(String relativePath, String text) {
        return validate(relativePath, text, steps::match);
    }

    /** Validates with a custom step matcher (e.g. project steps plus a generated step class). */
    public Report validate(String relativePath, String text, Function<String, List<StepDefinitionIndex.Match>> matcher) {
        FeatureIndex.ParsedText parsed = FeatureIndex.parseText(relativePath, text);
        Map<String, Object> details = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        parsed.problems().forEach(p -> errors.add("Gherkin: " + p.message()));
        details.put("feature", parsed.feature() == null ? null : parsed.feature().name());
        details.put("scenarios", parsed.scenarios().size());
        details.put("testCases", parsed.scenarios().stream().mapToInt(ScenarioInfo::runCount).sum());

        // Step bindings
        Set<String> undefined = new LinkedHashSet<>();
        List<Map<String, Object>> ambiguous = new ArrayList<>();
        List<Map<String, Object>> reused = new ArrayList<>();
        for (ScenarioInfo s : parsed.scenarios()) {
            if (s.steps().isEmpty()) {
                errors.add("Scenario '" + s.name() + "' has no steps");
            }
            if (s.steps().stream().noneMatch(st -> st.keyword().equals("Then"))) {
                warnings.add("Scenario '" + s.name() + "' has no Then step (nothing is verified)");
            }
            for (ScenarioInfo.StepInfo st : Stream.concat(s.backgroundSteps().stream(), s.steps().stream()).toList()) {
                for (String concrete : s.concreteTexts(st)) {
                    List<StepDefinitionIndex.Match> m = matcher.apply(concrete);
                    if (m.isEmpty()) {
                        undefined.add(concrete);
                    } else if (m.size() > 1) {
                        ambiguous.add(Map.of("step", concrete, "definitions", m.stream().map(x -> x.definition().expression()).toList()));
                    } else if (reused.stream().noneMatch(r -> r.get("expression").equals(m.get(0).definition().expression()))) {
                        reused.add(Map.of("expression", m.get(0).definition().expression(),
                                "method", m.get(0).definition().className() + "." + m.get(0).definition().method()));
                    }
                }
            }
        }
        if (!ambiguous.isEmpty()) {
            errors.add(ambiguous.size() + " step(s) match more than one definition (Cucumber would fail with AmbiguousStepDefinitions)");
        }
        details.put("reusedStepDefinitions", reused);
        details.put("undefinedSteps", List.copyOf(undefined));
        details.put("ambiguousSteps", ambiguous);

        // Duplicates against the existing suite
        List<Map<String, Object>> duplicates = new ArrayList<>();
        for (ScenarioInfo s : parsed.scenarios()) {
            for (ScenarioInfo existing : features.scenarios()) {
                if (existing.file().equals(s.file())) {
                    continue;
                }
                boolean sameName = existing.name().equalsIgnoreCase(s.name());
                double overlap = s.normalizedSteps().equals(existing.normalizedSteps()) ? 1.0
                        : DiscoveryTools.jaccard(s.normalizedSteps(), existing.normalizedSteps());
                if (sameName || overlap >= 0.9) {
                    duplicates.add(Map.of("scenario", s.name(), "existing", existing.id() + " " + existing.name(),
                            "reason", sameName ? "same name" : "same steps (" + Math.round(overlap * 100) + "% overlap)"));
                }
            }
        }
        if (duplicates.stream().anyMatch(d -> "same name".equals(d.get("reason")) || String.valueOf(d.get("reason")).contains("100%"))) {
            errors.add("Duplicates an existing scenario (see duplicates)");
        } else if (!duplicates.isEmpty()) {
            warnings.add("Very similar to existing scenarios (see duplicates); consider a Scenario Outline row instead");
        }
        details.put("duplicates", duplicates);

        // Tags
        Set<String> known = new TreeSet<>(CONVENTION_TAGS);
        features.scenarios().forEach(s -> known.addAll(s.tags()));
        List<String> tagIssues = new ArrayList<>();
        for (ScenarioInfo s : parsed.scenarios()) {
            Set<String> tags = new LinkedHashSet<>(s.tags());
            s.examples().forEach(e -> tags.addAll(e.tags()));
            if (tags.stream().noneMatch(LAYER_TAGS::contains)) {
                tagIssues.add("'" + s.name() + "' needs one layer tag " + LAYER_TAGS);
            }
            if (tags.stream().noneMatch(SCOPE_TAGS::contains)) {
                tagIssues.add("'" + s.name() + "' needs one scope tag " + SCOPE_TAGS);
            }
            if (tags.contains("@quarantine")) {
                tagIssues.add("'" + s.name() + "' is @quarantine: new scenarios must not start quarantined");
            }
            for (String t : tags) {
                if (!t.equals(t.toLowerCase(Locale.ROOT)) || !t.matches("@[a-z0-9_-]+")) {
                    tagIssues.add("Tag " + t + " must be lower-case letters, digits, '-' or '_'");
                } else if (!known.contains(t)) {
                    warnings.add("New tag " + t + " (not used anywhere yet): add it to the tag conventions if intended");
                }
            }
        }
        if (!tagIssues.isEmpty()) {
            errors.add("Tag conventions not met (see tagIssues)");
        }
        details.put("tagIssues", tagIssues);

        // Secrets: features describe roles ("the administrator"), never real credentials or card numbers
        if (!Redactor.redact(text).equals(text)) {
            errors.add("Contains a literal credential, token or card number: use a parameter type such as {account} or {card}");
        }

        details.put("errors", errors);
        details.put("warnings", warnings);
        boolean valid = errors.isEmpty();
        details.put("verdict", !valid ? "INVALID" : undefined.isEmpty() ? "VALID: every step uses an existing step definition"
                : "VALID GHERKIN, " + undefined.size() + " undefined step(s): generate or write step definitions first");
        return new Report(valid, valid && undefined.isEmpty(), details);
    }
}
