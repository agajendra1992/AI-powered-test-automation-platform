package io.qaplatform.mcp.tools;

import io.qaplatform.mcp.util.PathNames;
import io.cucumber.tagexpressions.Expression;
import io.cucumber.tagexpressions.TagExpressionException;
import io.cucumber.tagexpressions.TagExpressionParser;
import io.qaplatform.mcp.config.McpConfig;
import io.qaplatform.mcp.discovery.FeatureIndex;
import io.qaplatform.mcp.discovery.ScenarioInfo;
import io.qaplatform.mcp.execution.ExecutionRecord;
import io.qaplatform.mcp.execution.ExecutionRequest;
import io.qaplatform.mcp.execution.ExecutionService;
import io.qaplatform.mcp.execution.ExecutionStatus;
import io.qaplatform.mcp.reporting.ReportReader;
import io.qaplatform.mcp.security.Principal;
import io.qaplatform.mcp.security.Redactor;
import io.qaplatform.mcp.security.Role;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tools that start, follow, cancel and approve framework runs. Every run is the framework's own
 * {@code mvn test} with a TestNG suite; tools only choose validated parameters.
 */
public final class ExecutionTools {

    public static final Set<String> BROWSERS = Set.of("chromium", "firefox", "webkit", "chrome", "msedge");
    public static final Pattern TAG_EXPRESSION = Pattern.compile("[@A-Za-z0-9_\\-()\\s]+");
    static final Pattern SUITE_FILE = Pattern.compile("testng-[a-z0-9-]+\\.xml");
    private static final String QUARANTINE_EXCLUSION = "not @quarantine";

    private final McpConfig config;
    private final ExecutionService executions;
    private final FeatureIndex features;
    private final ReportReader reports;

    public ExecutionTools(McpConfig config, ExecutionService executions, FeatureIndex features, ReportReader reports) {
        this.config = config;
        this.executions = executions;
        this.features = features;
        this.reports = reports;
    }

    public List<ToolSpec> tools() {
        List<ToolSpec> tools = new ArrayList<>();
        tools.add(suiteTool("run_smoke_tests", "Run smoke tests", "testng-smoke.xml",
                "Runs the smoke suite (@smoke, quarantine excluded) through the Java Cucumber + TestNG framework."));
        tools.add(suiteTool("run_regression_tests", "Run full regression", "testng-regression.xml",
                "Runs the COMPLETE regression suite (every non-quarantined scenario). Nothing is skipped or sampled."));
        tools.add(suiteTool("run_ui_tests", "Run UI tests", "testng-ui.xml", "Runs @ui scenarios that are not hybrid @e2e flows (quarantine excluded), like the framework's UiRunner."));
        tools.add(suiteTool("run_api_tests", "Run API tests", "testng-api.xml",
                "Runs @api scenarios that are not hybrid @e2e flows (quarantine excluded), like the framework's ApiRunner. No browser is started."));
        tools.add(suiteTool("run_e2e_tests", "Run end-to-end tests", "testng-e2e.xml", "Runs all @e2e hybrid UI + API scenarios."));

        tools.add(new ToolSpec("run_tests_by_tag", "Run scenarios by tag expression",
                "Runs the scenarios matching a Cucumber tag expression, e.g. '@payment and @regression' or '@api and not @negative'. "
                        + "@quarantine scenarios are excluded unless include_quarantined is true. Fails fast if nothing matches.",
                Role.RUNNER, false,
                common(Schema.object().string("tag_expression", "Cucumber tag expression (tags, and/or/not, parentheses)", true, 200)
                        .bool("include_quarantined", "Also run @quarantine scenarios", false)),
                this::runByTag));

        tools.add(new ToolSpec("run_scenario_by_name", "Run one scenario",
                "Runs a single scenario identified by its id from list_scenarios (e.g. 'login/login.feature:12') or its exact name.",
                Role.RUNNER, false,
                common(Schema.object().string("scenario_id", "Scenario id <path>:<line> from list_scenarios", false, 300)
                        .string("name", "Exact scenario name (used when scenario_id is not given)", false, 200)),
                this::runScenario));

        tools.add(new ToolSpec("run_testng_suite", "Run a TestNG suite file",
                "Runs one of the project's TestNG suite files. Allowed values: " + suiteFiles() + ".",
                Role.RUNNER, false,
                common(Schema.object().choice("suite", "TestNG suite file", suiteFiles(), null)),
                (principal, args) -> submit(principal, args, "suite", args.choice("suite", suiteFiles(), null), null, null)));

        tools.add(new ToolSpec("get_execution_status", "Get execution status",
                "Status and results of a run started with a run_* tool. Set wait_seconds (max 60) to wait for it to finish; "
                        + "call repeatedly for long runs.",
                Role.VIEWER, true,
                Schema.object().string("execution_id", "Id returned by a run_* tool", true, 40)
                        .integer("wait_seconds", "Wait up to this many seconds for completion", 0, 0, 60),
                this::status));

        tools.add(new ToolSpec("cancel_execution", "Cancel a run",
                "Cancels a queued, awaiting-approval or running execution (stops Maven, the test JVM and browsers). "
                        + "Only the requester or an admin can cancel.",
                Role.RUNNER, false,
                Schema.object().string("execution_id", "Execution id", true, 40),
                (principal, args) -> view(executions.cancel(id(args), principal), false)));

        tools.add(new ToolSpec("list_pending_approvals", "List runs awaiting approval",
                "Runs that target an approval-protected environment (" + config.approvalEnvironments() + ") and wait for an admin.",
                Role.VIEWER, true, Schema.object(),
                (principal, args) -> ToolResult.of(Map.of("pending", executions.pendingApprovals().stream().map(r -> view(r, false).data()).toList()))));

        tools.add(new ToolSpec("approve_execution", "Approve a run",
                "Approves a run awaiting approval so it is queued. The approver must be an admin and a different person than the requester. "
                        + "AI assistants should only call this when the human admin explicitly asks to approve that execution id.",
                Role.ADMIN, false,
                Schema.object().string("execution_id", "Execution id", true, 40).string("note", "Reason or ticket reference", false, 300),
                (principal, args) -> view(executions.approve(id(args), principal, args.optionalString("note", 300, Args.TEXT)), false)));

        tools.add(new ToolSpec("reject_execution", "Reject a run",
                "Rejects a run awaiting approval. It will not run.",
                Role.ADMIN, false,
                Schema.object().string("execution_id", "Execution id", true, 40).string("reason", "Why it was rejected", true, 300),
                (principal, args) -> view(executions.reject(id(args), principal, args.string("reason", 300, Args.TEXT)), false)));
        return tools;
    }

    // ---------------------------------------------------------------- handlers

    private ToolSpec suiteTool(String name, String title, String suite, String description) {
        return new ToolSpec(name, title, description + " Returns an execution id immediately; follow it with get_execution_status.",
                Role.RUNNER, false, common(Schema.object()),
                (principal, args) -> submit(principal, args, name.replace("run_", "").replace("_tests", ""), suite, null, null));
    }

    private ToolResult runByTag(Principal principal, Args args) {
        String expression = args.string("tag_expression", 200, TAG_EXPRESSION);
        validateTagExpression(expression);
        String effective = args.bool("include_quarantined", false) ? expression : "(" + expression + ") and " + QUARANTINE_EXCLUSION;
        Expression parsed = TagExpressionParser.parse(effective);
        int matched = features.select(parsed).stream().mapToInt(ScenarioInfo::runCount).sum();
        if (matched == 0) {
            throw ToolException.invalid("No scenario matches '" + effective + "'. Use list_cucumber_tags to see the available tags.");
        }
        return submit(principal, args, "tag", "testng-regression.xml", effective, null);
    }

    private ToolResult runScenario(Principal principal, Args args) {
        ScenarioInfo scenario;
        if (args.has("scenario_id")) {
            String id = args.string("scenario_id", 300, DiscoveryTools.SCENARIO_ID);
            scenario = features.byId(id).orElseThrow(() -> ToolException.notFound("No scenario with id " + id + ". Use list_scenarios."));
        } else if (args.has("name")) {
            String name = args.string("name", 200, Args.TEXT);
            List<ScenarioInfo> found = features.byName(name);
            if (found.isEmpty()) {
                throw ToolException.notFound("No scenario named '" + name + "'. Use search_feature_files or list_scenarios.");
            }
            scenario = found.get(0);
        } else {
            throw ToolException.invalid("Provide scenario_id or name");
        }
        if (scenario.tags().contains("@quarantine")) {
            throw ToolException.conflict("Scenario '" + scenario.name() + "' is @quarantine; use run_tests_by_tag with include_quarantined=true if you really need it");
        }
        if (scenario.name().contains("<")) {
            throw ToolException.invalid("Scenario outline names with <placeholders> cannot be selected by name; use run_tests_by_tag");
        }
        return submit(principal, args, "scenario", "testng-regression.xml", null, List.of(scenario.name()));
    }

    private ToolResult submit(Principal principal, Args args, String kind, String suite, String tags, List<String> scenarioNames) {
        String environment = args.choice("environment", config.allowedEnvironments(), "local");
        String browser = args.choice("browser", BROWSERS, "chromium");
        int threads = args.integer("threads", 4, 1, 8);
        int retries = args.integer("retries", 0, 0, 2);
        ExecutionRequest request = new ExecutionRequest(kind, suite, tags, scenarioNames, environment, browser, threads, retries);
        ExecutionRecord record = executions.submit(request, principal);
        ToolResult view = view(record, false);
        Map<String, Object> data = new LinkedHashMap<>(view.data());
        data.put("next", record.status() == ExecutionStatus.AWAITING_APPROVAL
                ? "An admin other than " + principal.name() + " must call approve_execution with this execution_id."
                : "Call get_execution_status with execution_id=" + record.id() + " and wait_seconds=60 until status is final.");
        return ToolResult.of(data);
    }

    private ToolResult status(Principal principal, Args args) {
        String id = id(args);
        ExecutionRecord record = executions.await(id, Duration.ofSeconds(args.integer("wait_seconds", 0, 0, 60)));
        return view(record, true);
    }

    /** Public view of a run; with details, adds failed scenarios and (for build errors) the console tail. */
    ToolResult view(ExecutionRecord record, boolean details) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", record.id());
        data.put("status", record.status().name());
        data.put("final", record.status().terminal());
        data.put("message", record.message());
        data.put("request", record.request());
        data.put("requestedBy", record.requestedBy());
        data.put("approvedBy", record.approvedBy());
        data.put("createdAt", String.valueOf(record.createdAt()));
        data.put("startedAt", record.startedAt() == null ? null : record.startedAt().toString());
        data.put("finishedAt", record.finishedAt() == null ? null : record.finishedAt().toString());
        data.put("exitCode", record.exitCode());
        if (record.summary() != null) {
            Map<String, Object> s = record.summary();
            Map<String, Object> counts = new LinkedHashMap<>();
            for (String key : List.of("total", "passed", "failed", "skipped", "flaky", "retriedAttempts", "durationMs")) {
                counts.put(key, s.get(key));
            }
            data.put("summary", counts);
        }
        if (details && record.status().terminal()) {
            ReportReader.Source source = reports.resolve(record.id());
            List<Map<String, Object>> failed = reports.cucumberScenarios(source).stream()
                    .filter(r -> !"passed".equals(r.get("status"))).toList();
            if (!failed.isEmpty()) {
                data.put("failedScenarios", failed);
            }
            if (record.status() == ExecutionStatus.ERROR || record.status() == ExecutionStatus.TIMED_OUT) {
                data.put("consoleTail", reports.consoleTail(record.id(), 40).stream().map(Redactor::redact).toList());
            }
            data.put("evidence", "get_failed_scenarios, get_screenshot, get_trace_information and get_scenario_logs accept execution_id=" + record.id());
        }
        return ToolResult.of(data);
    }

    // ---------------------------------------------------------------- shared validation

    private Schema common(Schema schema) {
        return schema
                .choice("environment", "Target environment (approval needed for " + config.approvalEnvironments() + ")",
                        config.allowedEnvironments(), "local")
                .choice("browser", "Browser for UI scenarios", BROWSERS, "chromium")
                .integer("threads", "Parallel scenarios", 4, 1, 8)
                .integer("retries", "Retries per failed scenario (failed attempts stay visible)", 0, 0, 2);
    }

    public static void validateTagExpression(String expression) {
        for (String token : expression.replace("(", " ").replace(")", " ").trim().split("\\s+")) {
            if (!token.matches("and|or|not|@[A-Za-z0-9_-]+")) {
                throw ToolException.invalid("Invalid token '" + token + "' in tag expression: use @tags with and, or, not and parentheses");
            }
        }
        try {
            TagExpressionParser.parse(expression);
        } catch (TagExpressionException e) {
            throw ToolException.invalid("Invalid tag expression: " + e.getMessage());
        }
    }

    private static String id(Args args) {
        String id = args.string("execution_id", 40, ExecutionService.ID);
        return id;
    }

    private Set<String> suiteFiles() {
        try (Stream<Path> files = Files.list(config.projectDir())) {
            return files.map(p -> PathNames.name(p))
                    .filter(n -> SUITE_FILE.matcher(n).matches())
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
