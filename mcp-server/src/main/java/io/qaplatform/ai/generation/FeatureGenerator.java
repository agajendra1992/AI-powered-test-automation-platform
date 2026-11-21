package io.qaplatform.ai.generation;

import io.qaplatform.ai.llm.AiClient;
import io.qaplatform.ai.llm.LlmException;
import io.qaplatform.ai.llm.PromptManager;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.tools.ToolException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * User story + acceptance criteria → a draft feature file that reuses existing steps. The draft
 * is validated (syntax, step bindings, duplicates, tags, secrets), repaired once using the
 * validator's findings if needed, and stored for human review. It is never written to src/.
 */
public final class FeatureGenerator {

    private final AiClient ai;
    private final GherkinValidator validator;
    private final ProjectContext context;
    private final DraftStore drafts;

    public FeatureGenerator(AiClient ai, GherkinValidator validator, ProjectContext context, DraftStore drafts) {
        this.ai = ai;
        this.validator = validator;
        this.context = context;
        this.drafts = drafts;
    }

    public Map<String, Object> generate(Principal requester, String story, String criteria, String apiSpec, String domain, String layer) {
        if (!ai.enabled()) {
            throw new ToolException("unavailable", "AI is not configured (QA_AI_PROVIDER). Write the feature by hand and check it with validate_feature_file.");
        }
        String domainTag = domain == null ? null : "@" + domain.replace("@", "").toLowerCase(Locale.ROOT);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("story", PromptManager.untrusted("user story", story, 4000));
        values.put("criteria", PromptManager.untrusted("acceptance criteria", criteria == null ? "(none given)" : criteria, 6000));
        values.put("api_spec", PromptManager.untrusted("API specification", apiSpec == null ? "(none given)" : apiSpec, 8000));
        values.put("domain", domainTag == null ? "(choose from the story)" : domainTag);
        values.put("layer", layer);
        values.put("steps", context.stepCatalog());
        values.put("parameter_types", context.parameterTypes());
        values.put("example", PromptManager.untrusted("example feature file", context.exampleFeature(domainTag), 6000));
        values.put("tag_rules", tagRules());

        String draftId = drafts.newId();
        String rel = (domainTag == null ? "generated" : domainTag.substring(1)) + "/" + draftId.substring(6) + ".feature";
        AiClient.Answer first;
        try {
            first = ai.text("feature-generation", values);
        } catch (LlmException e) {
            throw new ToolException("unavailable", "AI call failed: " + e.kind() + ": " + e.getMessage());
        }
        String feature = first.text();
        GherkinValidator.Report report = validator.validate(rel, feature);
        int repairs = 0;
        if (!report.readyToRun()) {
            Map<String, String> repair = new LinkedHashMap<>(values);
            repair.put("draft", PromptManager.untrusted("your previous draft", feature, 12000));
            repair.put("problems", problems(report));
            try {
                String second = ai.text("feature-repair", repair).text();
                GherkinValidator.Report secondReport = validator.validate(rel, second);
                repairs = 1;
                if (better(secondReport, report)) {
                    feature = second;
                    report = secondReport;
                }
            } catch (LlmException e) {
                repairs = 0;
            }
        }
        String fileName = slug(String.valueOf(report.details().get("feature"))) + ".feature";
        String target = "src/test/resources/features/" + (domainTag == null ? "generated" : domainTag.substring(1)) + "/" + fileName;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("draftId", draftId);
        meta.put("kind", "feature");
        meta.put("status", "PENDING_REVIEW");
        meta.put("createdBy", requester.name());
        meta.put("createdAt", Instant.now().toString());
        meta.put("model", first.response().provider() + "/" + first.response().model());
        meta.put("promptVersion", "feature-generation v" + first.promptVersion());
        meta.put("repairRounds", repairs);
        meta.put("featureFile", fileName);
        meta.put("proposedPath", target);
        meta.put("storyExcerpt", story.length() > 160 ? story.substring(0, 160) + "..." : story);
        drafts.write(draftId, fileName, feature);
        drafts.writeJson(draftId, "feature-validation.json", report.details());
        drafts.writeJson(draftId, "meta.json", meta);

        Map<String, Object> result = new LinkedHashMap<>(meta);
        result.put("validation", report.details());
        result.put("feature", feature);
        result.put("next", report.readyToRun()
                ? "Review the feature. An admin can add it to the project with accept_generated_draft (never overwrites)."
                : report.valid() ? "Generate step definitions for the undefined steps with generate_step_definitions draft_id=" + draftId
                : "The draft is not valid (see validation.errors). Edit it by hand or generate again with more precise acceptance criteria.");
        return result;
    }

    static String problems(GherkinValidator.Report report) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> d = report.details();
        sb.append("Errors: ").append(d.get("errors")).append('\n');
        sb.append("Undefined steps (rewrite them with the existing step expressions if at all possible): ").append(d.get("undefinedSteps")).append('\n');
        sb.append("Ambiguous steps: ").append(d.get("ambiguousSteps")).append('\n');
        sb.append("Tag issues: ").append(d.get("tagIssues")).append('\n');
        sb.append("Duplicates of existing scenarios: ").append(d.get("duplicates"));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static boolean better(GherkinValidator.Report candidate, GherkinValidator.Report current) {
        if (candidate.valid() != current.valid()) {
            return candidate.valid();
        }
        int a = ((List<Object>) candidate.details().get("undefinedSteps")).size();
        int b = ((List<Object>) current.details().get("undefinedSteps")).size();
        return a < b;
    }

    static String tagRules() {
        return "Every scenario (directly or through the Feature line) has exactly one layer tag " + GherkinValidator.LAYER_TAGS
                + ", at least one scope tag " + GherkinValidator.SCOPE_TAGS + ", the domain tag, and @positive or @negative. "
                + "Optional: @critical. Never @quarantine. Tags are lower case.";
    }

    static String slug(String text) {
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        if (s.isEmpty()) {
            s = "generated_feature";
        }
        return s.length() > 60 ? s.substring(0, 60) : s;
    }
}
