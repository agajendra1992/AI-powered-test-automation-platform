# Intelligent QA

These are MCP tools that use the project's sources and **run history** to decide what to run,
spot flaky and broken tests, connect related failures, and keep the suite healthy. Every result is
computed deterministically first. AI, when enabled, only adds recommendations, which are labelled
and validated.

## Run history

History is every finished MCP run plus results imported from CI: unpack the CI artifacts somewhere
inside the project, then call `import_execution_results` (runner):

```json
{"results_dir": "ci-artifacts/412/target", "label": "CI #412 shard 2"}
```

Only the framework's result files are copied (`execution-summary.json`, `test-failures/`,
`cucumber-reports/`, `allure-results/`, `logs/`, `artifacts/`). The run's status comes from the
summary, exactly as for MCP runs. Per scenario, each run's outcome is `passed`, `failed`,
`skipped` or `flaky` (failed, then passed on a retry, as the framework reports in
`flakyScenarios`).

## Tools

| Tool | Role | What it does |
|---|---|---|
| `suggest_regression_tests` | viewer | **Impact-based selection.** Changed files come from `changed_files` or **`changed_since`** (a Git ref; `git diff --name-only <ref>` plus untracked files). Files are traced to scenarios: feature → its scenarios; step class → scenarios using its steps; page object or service → step classes using it (one level of indirection); framework-wide files → full regression. **History:** scenarios in the same domain that failed or were flaky in the last 10 runs are added and labelled. **Knowledge base** (if configured): related scenarios for the change description, not added to the plan. **AI** (runner): extra tag expressions, validated, not added. Saves an executable plan, and always states the full-regression option |
| `compare_with_full_regression` | viewer | Targeted run vs full-regression run: failures the selection caught, failures **only the full run found** (escaped selection), and scenarios whose result differed (flaky or environment-dependent). Warns when the "full" run was filtered |
| `get_flaky_tests` | viewer | Per scenario over recent runs: passes, failures, passed-after-retry, status flips, distinct errors, environments and browsers, and a classification: **FLAKY** (retry-pass, or ≥ 2 flips), **CONSISTENTLY_FAILING** (last 2+ runs failed, so a real defect or a broken test rather than flakiness), **RECOVERED**, **STABLE**. History string: `p` passed, `f` failed, `F` flaky, oldest first |
| `correlate_failures` | viewer | Failures grouped by normalised error signature (ids, numbers and uuids removed): occurrences, runs, scenarios, environments, browsers, rule-based category, and hints: *N scenarios failed the same way in one run* (shared cause: environment, data, a shared step), *recurring in N runs* (a known issue), *only seen on <browser>* |
| `suggest_missing_scenarios` | viewer | Per-domain balance and OpenAPI endpoints without API automation (Phase 4), plus **requirement coverage**: each acceptance criterion maps to the scenario containing most of its words: `LIKELY COVERED` (≥ 50 %, check it) or `NO MATCHING SCENARIO` (a probable gap) |
| `find_duplicate_scenarios` | viewer | Identical or near-identical step sequences (Phase 3) |
| `suggest_test_maintenance` | viewer (AI plan: runner) | Findings with an id, severity, location and suggested action: unused step definitions, duplicate scenarios, scenarios over 12 steps, single-row outlines, quarantined scenarios, brittle locators (XPath, CSS classes, positional), flaky and consistently failing scenarios. With AI, a prioritised plan whose steps must reference real finding ids (others are dropped) |
| `analyze_scenario_failure` | viewer | Evidence, rule category, similar earlier failures and knowledge-base matches (Phases 4–5) |

## Selection workflow (spec section 18)

1. Identify changed files: `suggest_regression_tests changed_since=origin/main`.
2. Map them to scenarios: file tracing, deterministic.
3. Add recent failures and flaky scenarios from the same area: run history.
4. Add related knowledge: RAG, shown separately.
5. Add AI recommendations: validated tags, shown separately.
6. Generate a plan: `plan` in the response.
7. Get approval where needed: staging plans wait for an admin.
8. Execute through TestNG + Cucumber: `execute_test_plan`.
9. Compare with the full regression: `compare_with_full_regression`, for example nightly.

AI never removes scenarios from a plan and never skips mandatory regression.
`run_regression_tests` always runs every non-quarantined scenario.
