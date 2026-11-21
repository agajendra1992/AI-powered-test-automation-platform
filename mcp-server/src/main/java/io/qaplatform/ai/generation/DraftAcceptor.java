package io.qaplatform.ai.generation;

import io.qaplatform.mcp.util.PathNames;
import io.qaplatform.mcp.discovery.StepDefinitionIndex;
import io.qaplatform.mcp.security.AuditLog;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.tools.ToolException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The only path from a generated draft into the project, and only on an admin's explicit request.
 * All checks are re-run; files are created, never overwritten; the decision is audited. The
 * result still goes through normal code review (the files are ordinary uncommitted changes).
 */
public final class DraftAcceptor {

    private final DraftStore drafts;
    private final GherkinValidator validator;
    private final StepDefinitionGenerator stepGenerator;
    private final StepDefinitionIndex steps;
    private final Path projectDir;
    private final AuditLog audit;

    public DraftAcceptor(DraftStore drafts, GherkinValidator validator, StepDefinitionGenerator stepGenerator,
                         StepDefinitionIndex steps, Path projectDir, AuditLog audit) {
        this.drafts = drafts;
        this.validator = validator;
        this.stepGenerator = stepGenerator;
        this.steps = steps;
        this.projectDir = projectDir;
        this.audit = audit;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> accept(String draftId, Principal admin, String note) {
        Map<String, Object> meta = drafts.meta(draftId);
        if (!"PENDING_REVIEW".equals(meta.get("status"))) {
            throw ToolException.conflict("Draft " + draftId + " is " + meta.get("status"));
        }
        String featureFile = String.valueOf(meta.get("featureFile"));
        String feature = drafts.read(draftId, featureFile);
        String java = meta.get("javaFile") == null ? null : drafts.read(draftId, String.valueOf(meta.get("javaFile")));
        String javaRel = java == null ? null : String.valueOf(meta.get("javaProposedPath"));

        Function<String, List<StepDefinitionIndex.Match>> matcher = java == null ? steps::match : steps.withExtraSource(java, javaRel);
        GherkinValidator.Report report = validator.validate(featureFile, feature, matcher);
        if (!report.readyToRun()) {
            throw ToolException.conflict("Draft is not ready: " + report.details().get("verdict") + " " + report.details().get("errors"));
        }
        if (java != null) {
            List<String> undefinedBefore = (List<String>) validator.validate(featureFile, feature).details().get("undefinedSteps");
            Map<String, Object> check = stepGenerator.check(String.valueOf(meta.get("javaFile")).replace(".java", ""), javaRel, java,
                    featureFile, feature, undefinedBefore);
            if (!Boolean.TRUE.equals(check.get("passed"))) {
                throw ToolException.conflict("Generated step class fails its checks: " + check.get("problems"));
            }
        }
        Path featureTarget = safeTarget(String.valueOf(meta.get("proposedPath")), "src/test/resources/features/");
        Path javaTarget = java == null ? null : safeTarget(javaRel, "src/test/java/io/qaplatform/bdd/steps/");
        for (Path target : java == null ? List.of(featureTarget) : List.of(featureTarget, javaTarget)) {
            if (Files.exists(target)) {
                throw ToolException.conflict("Refusing to overwrite existing file " + projectDir.relativize(target));
            }
        }
        List<String> written = new ArrayList<>();
        if (javaTarget != null) {
            create(javaTarget, java);
            written.add(projectDir.relativize(javaTarget).toString());
        }
        create(featureTarget, feature);
        written.add(projectDir.relativize(featureTarget).toString());

        meta.put("status", "ACCEPTED");
        meta.put("acceptedBy", admin.name());
        meta.put("acceptedAt", Instant.now().toString());
        meta.put("acceptNote", note == null ? "" : note);
        meta.put("writtenFiles", written);
        drafts.writeJson(draftId, "meta.json", meta);
        audit.record("draft_accepted", admin, "accept_generated_draft", "accepted",
                Map.of("draftId", draftId, "files", String.join(", ", written), "note", note == null ? "" : note));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("draftId", draftId);
        result.put("status", "ACCEPTED");
        result.put("writtenFiles", written);
        result.put("next", "Files are new, uncommitted changes. Run `mvn test-compile`, run the new scenarios "
                + "(run_tests_by_tag or run_scenario_by_name), then review and merge through a pull request.");
        return result;
    }

    private Path safeTarget(String relative, String requiredPrefix) {
        if (relative == null || !relative.startsWith(requiredPrefix) || relative.contains("..")) {
            throw ToolException.conflict("Unexpected target path " + relative);
        }
        Path target = projectDir.resolve(relative).normalize();
        if (!target.startsWith(projectDir.resolve(requiredPrefix).normalize())) {
            throw ToolException.conflict("Unexpected target path " + relative);
        }
        return target;
    }

    private static void create(Path target, String content) {
        try {
            Files.createDirectories(PathNames.parent(target));
            Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            throw ToolException.conflict("Refusing to overwrite existing file " + target.getFileName());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
