package io.qaplatform.ai;

import io.qaplatform.ai.analysis.FailureAnalyzer;
import io.qaplatform.ai.generation.DraftAcceptor;
import io.qaplatform.ai.generation.DraftStore;
import io.qaplatform.ai.generation.FeatureGenerator;
import io.qaplatform.ai.generation.GherkinValidator;
import io.qaplatform.ai.generation.StepDefinitionGenerator;
import io.qaplatform.ai.llm.AiClient;
import io.qaplatform.ai.planning.TestPlanner;
import io.qaplatform.ai.recommend.CoverageAdvisor;
import io.qaplatform.ai.recommend.ExecutionSummarizer;
import io.qaplatform.ai.recommend.RegressionAdvisor;
import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.security.Role;
import io.qaplatform.mcp.tools.Args;
import io.qaplatform.mcp.tools.ExecutionTools;
import io.qaplatform.mcp.tools.Schema;
import io.qaplatform.mcp.tools.ToolException;
import io.qaplatform.mcp.tools.ToolResult;
import io.qaplatform.mcp.tools.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP tools for the AI layer. Every tool works without a model (deterministic results); model
 * calls need the runner role because they spend the token budget and send (redacted) project
 * data to the configured provider. Generated artefacts only reach src/ through
 * accept_generated_draft (admin).
 */
public final class AiTools {

    private static final Set<String> LAYERS = Set.of("ui", "api", "e2e", "any");
    private static final Pattern DOMAIN = Pattern.compile("@?[a-z0-9_-]{2,30}");
    private static final Pattern FILE_PATH = Pattern.compile("[A-Za-z0-9_./-]{1,300}");
    private static final String EXECUTION_ID = "Execution id, 'latest' or 'workspace'";

    private final McpConfig config;
    private final AiClient ai;
    private final FailureAnalyzer analyzer;
    private final GherkinValidator validator;
    private final FeatureGenerator featureGenerator;
    private final StepDefinitionGenerator stepGenerator;
    private final DraftStore drafts;
    private final DraftAcceptor acceptor;
    private final TestPlanner planner;
    private final RegressionAdvisor regression;
    private final CoverageAdvisor coverage;
    private final ExecutionSummarizer summarizer;

    public AiTools(McpConfig config, AiClient ai, FailureAnalyzer analyzer, GherkinValidator validator, FeatureGenerator featureGenerator,
                   StepDefinitionGenerator stepGenerator, DraftStore drafts, DraftAcceptor acceptor, TestPlanner planner,
                   RegressionAdvisor regression, CoverageAdvisor coverage, ExecutionSummarizer summarizer) {
        this.config = config;
        this.ai = ai;
        this.analyzer = analyzer;
        this.validator = validator;
        this.featureGenerator = featureGenerator;
        this.stepGenerator = stepGenerator;
        this.drafts = drafts;
        this.acceptor = acceptor;
        this.planner = planner;
        this.regression = regression;
        this.coverage = coverage;
        this.summarizer = summarizer;
    }

    /** Model calls only for runners and above; viewers get the deterministic part. */
    private static boolean aiAllowed(Principal p, Args a) {
        return a.bool("use_ai", true) && p.atLeast(Role.RUNNER);
    }

    private static Map<String, Object> withAiNote(Principal p, Args a, Map<String, Object> data) {
        if (a.bool("use_ai", true) && !p.atLeast(Role.RUNNER)) {
            Map<String, Object> m = new LinkedHashMap<>(data);
            m.put("aiNote", "Model calls need the runner role; deterministic results only.");
            return m;
        }
        return data;
    }

    public List<ToolSpec> tools() {
        List<ToolSpec> t = new ArrayList<>();
        t.add(new ToolSpec("get_ai_status", "AI provider status",
                "Which LLM provider/model is configured (or why AI is disabled) and today's token use against the budget.",
                Role.VIEWER, true, Schema.object(), (p, a) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("enabled", ai.enabled());
                    m.put("provider", ai.provider().name());
                    m.put("model", ai.provider().model());
                    if (!ai.enabled()) {
                        m.put("reason", ai.provider().disabledReason());
                    }
                    m.put("usedTodayTokens", ai.usage().usedToday());
                    m.put("dailyBudgetTokens", ai.usage().dailyBudget());
                    m.put("principle", "AI is optional: every tool returns deterministic results without it, and AI never changes a test result.");
                    return ToolResult.of(m);
                }));
        t.add(new ToolSpec("get_ai_usage", "AI token usage", "Token usage per provider/model/purpose and per day (no prompt text is stored).",
                Role.VIEWER, true, Schema.object(), (p, a) -> ToolResult.of(ai.usage().report())));

        t.add(new ToolSpec("analyze_scenario_failure", "Analyse a failed scenario",
                "Collects the evidence (failure record, failing step, browser diagnostics, API calls, logs, similar earlier failures), "
                        + "classifies it with deterministic rules into one of 11 categories and, with AI, adds an unconfirmed hypothesis "
                        + "whose cited evidence is verified. The test result is never changed.",
                Role.VIEWER, true,
                Schema.object().string("scenario_name", "Exact scenario name (see get_failed_scenarios)", true, 200)
                        .string("execution_id", EXECUTION_ID, false, 40)
                        .bool("use_ai", "Ask the configured model for a second opinion (runner role)", true),
                (p, a) -> ToolResult.of(withAiNote(p, a, analyzer.analyze(a.optionalString("execution_id", 40, Pattern.compile("latest|workspace|run-[0-9a-f-]+")),
                        a.string("scenario_name", 200, Args.TEXT), aiAllowed(p, a))))));

        t.add(new ToolSpec("validate_feature_file", "Validate a feature file (no AI)",
                "Checks Gherkin text before it is added: syntax, every step bound to exactly one step definition (or listed as undefined), "
                        + "duplicates of existing scenarios, tag conventions, literal secrets.",
                Role.VIEWER, true,
                Schema.object().string("content", "Feature file text", true, 50_000)
                        .string("path", "Intended path under features/, e.g. payment/refunds.feature", false, 200),
                (p, a) -> {
                    String path = a.has("path") ? a.string("path", 200, FILE_PATH) : "draft/new.feature";
                    var report = validator.validate(path, a.string("content", 50_000, null));
                    Map<String, Object> m = new LinkedHashMap<>(report.details());
                    m.put("valid", report.valid());
                    m.put("readyToRun", report.readyToRun());
                    return ToolResult.of(m);
                }));

        t.add(new ToolSpec("generate_feature_file", "Generate a feature file (AI)",
                "Drafts a feature file from a user story and acceptance criteria, reusing existing steps; validates it, repairs it once "
                        + "if needed, and stores it as a draft for human review. Never writes to the project.",
                Role.RUNNER, false,
                Schema.object().string("user_story", "As a ... I want ... so that ...", true, 4000)
                        .string("acceptance_criteria", "Acceptance criteria / requirements", false, 6000)
                        .string("api_specification", "Optional API specification excerpt (OpenAPI JSON/YAML or prose)", false, 8000)
                        .string("domain", "Domain tag without @, e.g. payment, user, login", false, 30)
                        .choice("layer", "ui, api, e2e or any", LAYERS, "any"),
                (p, a) -> ToolResult.of(featureGenerator.generate(p, a.string("user_story", 4000, null),
                        a.optionalString("acceptance_criteria", 6000, null), a.optionalString("api_specification", 8000, null),
                        a.optionalString("domain", 30, DOMAIN), a.choice("layer", LAYERS, "any")))));

        t.add(new ToolSpec("generate_step_definitions", "Generate step definitions (AI)",
                "For a feature draft with undefined steps: writes a step-definition class following the project's conventions, then "
                        + "checks it (static rules, compiles against the framework, binds every undefined step, creates no ambiguity). "
                        + "Stored with the draft; never written to the project.",
                Role.RUNNER, false,
                Schema.object().string("draft_id", "Draft id from generate_feature_file", true, 40),
                (p, a) -> ToolResult.of(stepGenerator.generate(p, a.string("draft_id", 40, DraftStore.ID)))));

        t.add(new ToolSpec("list_generated_drafts", "List generated drafts", "Generated feature/step drafts with their review status.",
                Role.VIEWER, true, Schema.object().integer("limit", "Maximum drafts", 20, 1, 100),
                (p, a) -> ToolResult.of(Map.of("drafts", drafts.list(a.integer("limit", 20, 1, 100))))));
        t.add(new ToolSpec("get_generated_draft", "Get a generated draft", "The draft's feature text, step class (if any) and validation results.",
                Role.VIEWER, true, Schema.object().string("draft_id", "Draft id", true, 40), (p, a) -> {
                    String id = a.string("draft_id", 40, DraftStore.ID);
                    Map<String, Object> meta = drafts.meta(id);
                    Map<String, Object> m = new LinkedHashMap<>(meta);
                    m.put("feature", drafts.read(id, String.valueOf(meta.get("featureFile"))));
                    m.put("featureValidation", drafts.read(id, "feature-validation.json"));
                    if (meta.get("javaFile") != null) {
                        m.put("java", drafts.read(id, String.valueOf(meta.get("javaFile"))));
                        m.put("javaValidation", drafts.read(id, "java-validation.json"));
                    }
                    return ToolResult.of(m);
                }));
        t.add(new ToolSpec("accept_generated_draft", "Accept a generated draft (admin)",
                "Re-runs every check and adds the draft's feature (and step class) to the project as NEW files. Never overwrites. "
                        + "Only call when a human admin has reviewed the draft and asks for it.",
                Role.ADMIN, false,
                Schema.object().string("draft_id", "Draft id", true, 40).string("note", "Review note or ticket", false, 300),
                (p, a) -> ToolResult.of(acceptor.accept(a.string("draft_id", 40, DraftStore.ID), p, a.optionalString("note", 300, Args.TEXT)))));

        t.add(new ToolSpec("plan_test_execution", "Plan a test run from a request",
                "Turns a request like 'run the payment regression on QA in Firefox' into a validated plan: selection, environment, "
                        + "browser, number of test cases, what is left out, approval needs. Nothing runs until execute_test_plan.",
                Role.VIEWER, true,
                Schema.object().string("request", "What to run, in plain language", true, 1000)
                        .bool("use_ai", "Interpret with the configured model (runner role); keyword rules otherwise", true),
                (p, a) -> ToolResult.of(withAiNote(p, a, planner.plan(p, a.string("request", 1000, Args.TEXT), aiAllowed(p, a))))));
        t.add(new ToolSpec("execute_test_plan", "Execute a test plan",
                "Starts a plan from plan_test_execution or suggest_regression_tests through the normal execution path "
                        + "(roles, approvals, timeouts and audit apply). Returns an execution id.",
                Role.RUNNER, false,
                Schema.object().string("plan_id", "Plan id", true, 40),
                (p, a) -> ToolResult.of(planner.execute(p, a.string("plan_id", 40, TestPlanner.PLAN_ID)))));

        t.add(new ToolSpec("suggest_regression_tests", "Suggest regression tests for a change",
                "Traces changed files to the scenarios that exercise them (or recommends the full regression for shared code), "
                        + "saves an executable plan, and with AI adds validated tag suggestions from the change description.",
                Role.VIEWER, true,
                Schema.object().string("changed_files", "Changed file paths, comma or newline separated (e.g. from git diff --name-only)", false, 20_000)
                        .string("changed_since", "Or: a Git ref (branch, tag, commit); changed files are read with git diff", false, 100)
                        .string("change_description", "What changed and why (optional, used by AI)", false, 4000)
                        .choice("environment", "Environment for the plan", config.allowedEnvironments(), "local")
                        .choice("browser", "Browser for the plan", ExecutionTools.BROWSERS, "chromium")
                        .bool("use_ai", "Ask the model for additional risk-based tags (runner role)", true),
                (p, a) -> {
                    List<String> files = new ArrayList<>();
                    if (a.has("changed_since")) {
                        files.addAll(io.qaplatform.ai.insights.GitChanges.since(config.projectDir(), a.string("changed_since", 100, null)));
                    }
                    if (a.has("changed_files")) {
                        for (String f : a.string("changed_files", 20_000, null).split("[,\\n]")) {
                            if (!f.isBlank()) {
                                files.add(f.strip());
                            }
                        }
                    }
                    if (files.isEmpty()) {
                        throw ToolException.invalid("Provide changed_files or changed_since (or there are no changes)");
                    }
                    if (files.size() > 500) {
                        throw ToolException.invalid("At most 500 changed files");
                    }
                    return ToolResult.of(withAiNote(p, a, regression.suggest(p, files, a.optionalString("change_description", 4000, null),
                            a.choice("environment", config.allowedEnvironments(), "local"), a.choice("browser", ExecutionTools.BROWSERS, "chromium"),
                            aiAllowed(p, a))));
                }));

        t.add(new ToolSpec("suggest_missing_scenarios", "Suggest missing scenarios",
                "Coverage gaps: per-domain balance (positive/negative, UI/API, smoke), API endpoints in the OpenAPI spec that no "
                        + "API automation calls, and with AI, scenario ideas for the given requirements (checked against existing names).",
                Role.VIEWER, true,
                Schema.object().string("domain", "Domain tag without @ (optional)", false, 30)
                        .string("requirements", "User stories / acceptance criteria to compare with (for AI ideas)", false, 8000)
                        .string("api_specification", "OpenAPI document as JSON (optional)", false, 200_000)
                        .bool("fetch_openapi", "Fetch /openapi.json from the local application", false)
                        .bool("use_ai", "Ask the model for scenario ideas (runner role)", true),
                (p, a) -> ToolResult.of(withAiNote(p, a, coverage.suggest(a.optionalString("domain", 30, DOMAIN), a.optionalString("requirements", 8000, null),
                        a.optionalString("api_specification", 200_000, null), a.bool("fetch_openapi", false), aiAllowed(p, a))))));

        t.add(new ToolSpec("summarize_execution", "Summarise a run",
                "Facts of a run (status, counts, failed scenarios with a rule-based category each) and, with AI, a short narrative "
                        + "that is discarded if it contradicts the facts.",
                Role.VIEWER, true,
                Schema.object().string("execution_id", EXECUTION_ID, false, 40).bool("use_ai", "Add a model-written narrative (runner role)", true),
                (p, a) -> ToolResult.of(withAiNote(p, a, summarizer.summarize(
                        a.optionalString("execution_id", 40, Pattern.compile("latest|workspace|run-[0-9a-f-]+")), aiAllowed(p, a))))));
        return t;
    }
}
