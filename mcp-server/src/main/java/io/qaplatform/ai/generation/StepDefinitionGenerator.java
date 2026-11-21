package io.qaplatform.ai.generation;

import io.qaplatform.ai.llm.AiClient;
import io.qaplatform.ai.llm.LlmException;
import io.qaplatform.ai.llm.PromptManager;
import io.qaplatform.mcp.discovery.FeatureIndex;
import io.qaplatform.mcp.discovery.ScenarioInfo;
import io.qaplatform.mcp.discovery.StepDefinitionIndex;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.security.Redactor;
import io.qaplatform.mcp.tools.ToolException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes a new step-definition class for a draft's undefined steps, following the project's
 * conventions (thin steps over page objects/services, ScenarioContext for state). The class must
 * pass static checks, compile against the real framework, bind every previously undefined step
 * and not make any existing step ambiguous. It is stored as a draft; nothing in src/ changes.
 */
public final class StepDefinitionGenerator {

    record StaticRule(String description, Pattern pattern) {
    }

    static final List<StaticRule> FORBIDDEN = List.of(
            new StaticRule("JUnit, Selenium and Rest Assured are not allowed", Pattern.compile("import\\s+(org\\.junit|junit\\.|org\\.openqa\\.selenium|io\\.restassured)")),
            new StaticRule("hard sleeps are banned (Thread.sleep / waitForTimeout)", Pattern.compile("Thread\\.sleep\\(|waitForTimeout\\(")),
            new StaticRule("use SLF4J, not System.out/err", Pattern.compile("System\\.(out|err)\\.print")),
            new StaticRule("browsers come from ScenarioContext, never Playwright.create()", Pattern.compile("Playwright\\.create\\(")),
            new StaticRule("no static mutable state in step classes", Pattern.compile("\\bstatic\\s+(?!final)[\\w<>\\[\\], ]+\\s+\\w+\\s*[=;]")),
            new StaticRule("hooks belong in ScenarioHooks, not in generated steps", Pattern.compile("@(Before|After)(Step|All)?\\b")));

    private final AiClient ai;
    private final GherkinValidator validator;
    private final StepDefinitionIndex steps;
    private final FeatureIndex features;
    private final ProjectContext context;
    private final JavaCompileCheck compiler;
    private final DraftStore drafts;

    public StepDefinitionGenerator(AiClient ai, GherkinValidator validator, StepDefinitionIndex steps, FeatureIndex features,
                                   ProjectContext context, JavaCompileCheck compiler, DraftStore drafts) {
        this.ai = ai;
        this.validator = validator;
        this.steps = steps;
        this.features = features;
        this.context = context;
        this.compiler = compiler;
        this.drafts = drafts;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generate(Principal requester, String draftId) {
        if (!ai.enabled()) {
            throw new ToolException("unavailable", "AI is not configured (QA_AI_PROVIDER).");
        }
        Map<String, Object> meta = drafts.meta(draftId);
        if (!"feature".equals(meta.get("kind")) || !"PENDING_REVIEW".equals(meta.get("status"))) {
            throw ToolException.conflict("Draft " + draftId + " is " + meta.get("status") + "; only pending feature drafts can get step definitions");
        }
        String featureFile = String.valueOf(meta.get("featureFile"));
        String feature = drafts.read(draftId, featureFile);
        GherkinValidator.Report report = validator.validate(featureFile, feature);
        if (!report.valid()) {
            throw ToolException.conflict("The draft feature is not valid yet: " + report.details().get("errors"));
        }
        List<String> undefined = (List<String>) report.details().get("undefinedSteps");
        if (undefined.isEmpty()) {
            return Map.of("draftId", draftId, "message", "Every step already uses an existing step definition; nothing to generate.");
        }
        String domain = String.valueOf(meta.get("proposedPath")).replaceAll(".*/features/([^/]+)/.*", "$1");
        String className = className(String.valueOf(report.details().get("feature")));
        String rel = "src/test/java/io/qaplatform/bdd/steps/" + className + ".java";

        Map<String, String> values = new LinkedHashMap<>();
        values.put("class_name", className);
        values.put("undefined_steps", PromptManager.untrusted("undefined steps", String.join("\n", undefined), 6000));
        values.put("feature", PromptManager.untrusted("draft feature", feature, 10000));
        values.put("example_class", PromptManager.untrusted("example step class from this project", context.exampleStepClass(domain), 10000));
        values.put("context_api", context.scenarioContextApi());
        values.put("apis", context.pageAndServiceApis());
        values.put("parameter_types", context.parameterTypes());

        AiClient.Answer first;
        try {
            first = ai.text("step-generation", values);
        } catch (LlmException e) {
            throw new ToolException("unavailable", "AI call failed: " + e.kind() + ": " + e.getMessage());
        }
        String java = first.text();
        Map<String, Object> check = check(className, rel, java, featureFile, feature, undefined);
        int repairs = 0;
        if (!Boolean.TRUE.equals(check.get("passed"))) {
            Map<String, String> repair = new LinkedHashMap<>(values);
            repair.put("draft", PromptManager.untrusted("your previous class", java, 15000));
            repair.put("problems", String.valueOf(check.get("problems")));
            try {
                String second = ai.text("step-repair", repair).text();
                Map<String, Object> secondCheck = check(className, rel, second, featureFile, feature, undefined);
                repairs = 1;
                if (Boolean.TRUE.equals(secondCheck.get("passed")) || ((List<?>) secondCheck.get("problems")).size()
                        < ((List<?>) check.get("problems")).size()) {
                    java = second;
                    check = secondCheck;
                }
            } catch (LlmException e) {
                repairs = 0;
            }
        }
        drafts.write(draftId, className + ".java", java);
        drafts.writeJson(draftId, "java-validation.json", check);
        meta.put("javaFile", className + ".java");
        meta.put("javaProposedPath", rel);
        meta.put("javaPassed", check.get("passed"));
        meta.put("javaModel", first.response().provider() + "/" + first.response().model());
        meta.put("javaGeneratedAt", Instant.now().toString());
        meta.put("javaGeneratedBy", requester.name());
        meta.put("javaRepairRounds", repairs);
        drafts.writeJson(draftId, "meta.json", meta);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("draftId", draftId);
        result.put("className", className);
        result.put("proposedPath", rel);
        result.put("checks", check);
        result.put("java", java);
        result.put("next", Boolean.TRUE.equals(check.get("passed"))
                ? "Review the class (business rules belong in page objects/services, not steps). An admin can add feature + class with accept_generated_draft."
                : "Checks failed (see checks.problems). Fix the draft by hand or regenerate.");
        return result;
    }

    /** Static rules, compilation against the framework, and step binding with the new class. */
    public Map<String, Object> check(String className, String rel, String java, String featureFile, String feature, List<String> undefined) {
        List<String> problems = new ArrayList<>();
        if (!java.contains("package io.qaplatform.bdd.steps;")) {
            problems.add("Must be in package io.qaplatform.bdd.steps");
        }
        if (!Pattern.compile("public\\s+(final\\s+)?class\\s+" + className + "\\b").matcher(java).find()) {
            problems.add("Must declare public class " + className);
        }
        for (StaticRule r : FORBIDDEN) {
            if (r.pattern().matcher(java).find()) {
                problems.add("Convention: " + r.description());
            }
        }
        if (!Redactor.redact(java).equals(java)) {
            problems.add("Contains a literal credential, token or card number");
        }
        List<Map<String, Object>> compileErrors = new ArrayList<>();
        if (problems.isEmpty()) {
            for (JavaCompileCheck.Problem p : compiler.compile(Map.of(rel, java))) {
                compileErrors.add(Map.of("file", p.file(), "line", p.line(), "message", p.message()));
            }
            if (!compileErrors.isEmpty()) {
                problems.add("Does not compile against the framework (" + compileErrors.size() + " error(s)): " + compileErrors);
            }
        }
        List<String> stillUndefined = new ArrayList<>();
        List<String> newAmbiguities = new ArrayList<>();
        if (problems.isEmpty()) {
            Function<String, List<StepDefinitionIndex.Match>> matcher;
            try {
                matcher = steps.withExtraSource(java, rel);
            } catch (RuntimeException e) {
                matcher = null;
                problems.add("A step expression in the class is not a valid Cucumber expression: " + e.getMessage());
            }
            if (matcher != null) {
                for (String text : undefined) {
                    int n = matcher.apply(text).size();
                    if (n == 0) {
                        stillUndefined.add(text);
                    } else if (n > 1) {
                        newAmbiguities.add(text);
                    }
                }
                for (ScenarioInfo s : features.scenarios()) {
                    for (ScenarioInfo.StepInfo st : Stream.concat(s.backgroundSteps().stream(), s.steps().stream()).toList()) {
                        for (String text : s.concreteTexts(st)) {
                            if (matcher.apply(text).size() > 1) {
                                newAmbiguities.add(s.id() + ": " + text);
                            }
                        }
                    }
                }
                GherkinValidator.Report withClass = validator.validate(featureFile, feature, matcher);
                if (!withClass.readyToRun() && stillUndefined.isEmpty() && newAmbiguities.isEmpty()) {
                    problems.add("Feature still not runnable with this class: " + withClass.details().get("errors"));
                }
            }
            if (!stillUndefined.isEmpty()) {
                problems.add("Steps still undefined: " + stillUndefined);
            }
            if (!newAmbiguities.isEmpty()) {
                problems.add("Would make steps ambiguous: " + newAmbiguities);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", problems.isEmpty());
        result.put("problems", problems);
        result.put("compileErrors", compileErrors);
        result.put("definitions", StepDefinitionIndex.parseSteps(java, rel).stream().map(d -> d.keyword() + " " + d.expression()).toList());
        return result;
    }

    String className(String featureName) {
        String base = FeatureGenerator.slug(featureName);
        StringBuilder sb = new StringBuilder();
        for (String part : base.split("_")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        String name = (sb.length() == 0 ? "Generated" : sb.toString()) + "Steps";
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            name = "Generated" + name;
        }
        List<String> existing = context.existingStepClassNames();
        String candidate = name;
        for (int i = 2; existing.contains(candidate); i++) {
            candidate = name.replace("Steps", "") + i + "Steps";
        }
        return candidate;
    }
}
