# AI-Powered QA Platform

Enterprise test automation on **Playwright Java + Cucumber BDD + TestNG**, built so that AI
layers (MCP, LLM, RAG; later phases) can be added on top without the core ever depending on them.

| Responsibility | Technology |
|---|---|
| UI and API automation engine | Playwright Java (`APIRequestContext` for API) |
| Business-readable scenarios | Cucumber JVM 8 (feature files, steps, hooks, PicoContainer DI) |
| Execution, suites, parallelism | TestNG 7 (`AbstractTestNGCucumberTests`, parallel DataProvider) |
| Build | Maven, Java 21 |
| Reporting | Allure (Cucumber adapter only, so no duplicates), Cucumber HTML/JSON, `execution-summary.json` |
| Contract and performance tests | Pact consumer contract + provider verification (`docs/CONTRACT_TESTING.md`); k6 API load tests with thresholds (`docs/PERFORMANCE.md`) |
| Accessibility and visual checks | axe-core WCAG 2.1 AA scans (`docs/ACCESSIBILITY.md`); screenshot comparison against approved baselines (`docs/VISUAL_TESTING.md`) |
| Quality gate | Checkstyle (bans JUnit, Selenium, Rest Assured, hard sleeps, empty catches), SpotBugs, Trivy (CI) |
| CI / containers | GitHub Actions (PR smoke, sharded nightly regression, weekly cross-browser), Docker + Compose |
| AI integration | MCP server (`mcp-server/`, Java MCP SDK): discovery, execution through the framework, reports, approvals; optional LLM layer (Anthropic SDK, OpenAI, Gemini, local/Ollama) |
| Knowledge base | RAG service (`rag/`, Python FastAPI + ChromaDB): hybrid search with citations over features, code, docs and historical failures |
| AI orchestration (optional) | `ai-orchestration/` (Python FastAPI, LangChain + LangGraph): natural-language request → validated plan → human approval → run through MCP → results, failure analysis, recommendations |

Status per phase and acceptance criterion: [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

| Guide | Document |
|---|---|
| Setup and execution (this file) | Quick start, Running tests, Configuration, Troubleshooting |
| Architecture | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [decisions](docs/ARCHITECTURE_DECISIONS.md), [Phase 0 assessment](docs/CURRENT_ARCHITECTURE.md) |
| Onboarding | [docs/ONBOARDING.md](docs/ONBOARDING.md) |
| GitHub Actions and Docker | sections below, [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) |
| MCP connection | [docs/MCP.md](docs/MCP.md) |
| LLM configuration | [docs/AI.md](docs/AI.md) |
| RAG ingestion | [docs/RAG.md](docs/RAG.md) |
| LangChain + LangGraph workflow (`/ai/*` API) | [docs/AI_ORCHESTRATION.md](docs/AI_ORCHESTRATION.md) |
| Intelligent QA (selection, flaky, maintenance) | [docs/INTELLIGENT_QA.md](docs/INTELLIGENT_QA.md) |
| Operations | [docs/RUNBOOKS.md](docs/RUNBOOKS.md), monitoring in [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) |
| Security and performance validation | [docs/SECURITY_VALIDATION.md](docs/SECURITY_VALIDATION.md), [docs/PERFORMANCE.md](docs/PERFORMANCE.md) |
| Upgrades, code review | [docs/UPGRADING.md](docs/UPGRADING.md), [CODE_REVIEW_CHECKLIST.md](CODE_REVIEW_CHECKLIST.md) |

---

## Quick start (local)

Prerequisites: JDK 21+, Maven 3.9+, Python 3.11+ (for the bundled demo app).

```bash
# 1. Start the demo application under test (UI + REST API on :8000)
python3 -m venv sut/.venv && sut/.venv/bin/pip install -r sut/requirements.txt
sut/.venv/bin/uvicorn app:app --app-dir sut --port 8000
```

```bash
# 2. In another terminal: run the smoke suite (browsers download on first run)
mvn clean test
```

```bash
# 3. Build and open the Allure report
scripts/allure-report.sh
```

## Running tests

Every command below was executed and verified (see IMPLEMENTATION_STATUS.md).

| Goal | Command |
|---|---|
| Smoke (default) | `mvn clean test` or `mvn clean test -DsuiteXmlFile=testng-smoke.xml` |
| Full regression | `mvn clean test -DsuiteXmlFile=testng-regression.xml` |
| UI only | `mvn clean test -DsuiteXmlFile=testng-ui.xml` |
| API only (no browser launched) | `mvn clean test -DsuiteXmlFile=testng-api.xml` |
| Hybrid UI + API | `mvn clean test -DsuiteXmlFile=testng-e2e.xml` |
| By tag expression | `mvn clean test -Dcucumber.filter.tags="@smoke and @api"` |
| Exclude quarantine | `mvn clean test -DsuiteXmlFile=testng-regression.xml -Dcucumber.filter.tags="@regression and not @quarantine"` |
| Browser | `-Dbrowser=chromium` · `firefox` · `webkit` · `chrome` · `msedge` |
| Environment | `-Denv=local` · `qa` · `staging` (reads `config/<env>.properties`) |
| Threads | `-Dthreads=2` (scenario-level parallelism; default 4) |
| Shard (CI) | `-Dshard.index=0 -Dshard.total=3` |
| Headed | `-Dheadless=false` |
| Retry + trace on retry | `-Dretry.count=1 -Dtrace.mode=on-first-retry` |
| Framework unit tests | `mvn clean test -DsuiteXmlFile=testng-unit.xml` |

**Tag override semantics:** `-Dcucumber.filter.tags` *replaces* the selected runner's tag
expression (Cucumber's standard precedence). Combine it with `testng-regression.xml` when you
want to select from every non-quarantined scenario.

### Standard tags

`@smoke @regression @sanity @critical` (scope) · `@ui @api @e2e` (layer) ·
`@login @payment @user` (domain) · `@accessibility @visual` (non-functional) · `@positive @negative` (intent) ·
`@quarantine` (excluded by every runner, never retried) · `@noretry`.

## Docker

**Whole platform** (demo app, ChromaDB, RAG, MCP server; details in [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)):

```bash
scripts/dev-secrets.sh && docker compose --profile platform up -d --wait
```

**Test runner only:**

The test image is built on the official Playwright Java image (JDK, Maven and all three browsers).
`docker-compose.yml` runs it next to the demo app; results are written to `./target` on the host.

```bash
docker compose --profile tests build
```

```bash
docker compose up -d --wait sut
```

```bash
docker compose run --rm tests -DsuiteXmlFile=testng-regression.xml -Dbrowser=firefox
```

```bash
docker compose down
```

Any Maven/test flag can follow `tests` (the default is the smoke suite). If port 8000 is taken on
your machine, prefix the commands with `SUT_PORT=8001`; the containers talk to each other on
the compose network either way. To run the container against a real environment, skip the `sut`
service and pass `-Denv=qa` plus `-e AUTH_USERNAME -e AUTH_PASSWORD -e OAUTH_CLIENT_SECRET`.

## Continuous integration (GitHub Actions)

| Workflow | Trigger | What runs |
|---|---|---|
| `ci.yml` | every PR, push to `main` | Playwright/Docker version check, Checkstyle + compile + unit tests, then the smoke suite (Chromium), Pact contract tests and a 30 s k6 performance smoke |
| `performance.yml` | 10:00 IST Saturday, manual | k6 load (or stress) profile against the demo app, summary on the run page |
| `regression.yml` | 07:30 IST Mon–Fri, manual | Full regression split over N shards (default 3), merged Allure report |
| `cross-browser.yml` | 09:30 IST Sunday, manual | Full regression on Chromium, Firefox and WebKit in parallel, merged report |
| `docker.yml` | PRs that touch Docker/`sut/`/`pom.xml`, manual | Builds both images and runs smoke inside Docker |
| `run-suite.yml`, `report.yml` | called by the above | One suite × browser × shard; report merge |

Manual runs (**Actions → Regression → Run workflow**) take browser, environment, shard count,
tag expression and retries. Every job writes a results table to the run's summary page and
uploads `allure-results-*` and `summary-*`; failed jobs also upload `failure-evidence-*`
(screenshots, traces, logs, failure records; kept 7 days). The report job publishes
`allure-report`.

Schedules use the `Asia/Kolkata` time zone (`timezone:` next to each `cron:` line).

**Secrets setup (once per repository).** With `environment: local` the workflows start the bundled
demo app and need no secrets. For real environments, create GitHub Environments named `qa` and
`staging` (Settings → Environments), add `AUTH_USERNAME`, `AUTH_PASSWORD` and
`OAUTH_CLIENT_SECRET` to each, and add required reviewers to `staging` if runs there need
approval. Runs against qa/staging are recorded as deployments of that environment (an audit
trail of what was tested where); demo-app runs use `deployment: false`, so PRs show no
deployment entries. GitHub still lists an empty `local` environment after the first run.

**Pointing the scheduled runs at qa.** Scheduled regression and cross-browser runs use the
repository variable `SCHEDULED_TEST_ENV` (Settings → Secrets and variables → Actions →
Variables). Unset means `local` (demo app); set it to `qa` once the `qa` environment and its
secrets exist. Manual runs always use the environment you pick.

**Dependency updates.** `.github/dependabot.yml` opens grouped PRs every Monday 09:00 IST for the
pinned GitHub Actions, Maven (Playwright, Cucumber and Allure each in one PR; other minor/patch
updates together), and the demo app's pip packages and base image. Releases younger than 7 days
(30 for majors) are skipped. Every Dependabot PR runs the normal CI. **Playwright upgrades need
one manual line:** the CI version check fails the Dependabot PR until the `FROM` tag in
`Dockerfile` is changed to the same version (the error message gives the exact line).

## MCP server (AI assistants)

`mcp-server/` exposes the framework to MCP clients such as Claude Code: discover scenarios, run
suites, tags or single scenarios through the real Maven/TestNG build, and read results,
screenshots, traces and logs. It is read-only by default, staging runs need a second person's
approval, and every call is audited. The setup guide, tool list and security controls are in
[docs/MCP.md](docs/MCP.md).

```bash
mvn -f mcp-server/pom.xml package
```

Then copy `mcp-server/examples/claude-code.mcp.json` to `.mcp.json` and restart Claude Code.

**AI features** (failure analysis, natural-language test plans, feature and step generation,
regression and coverage suggestions) are off by default and every tool works without them. Turn
them on with, for example, `QA_AI_PROVIDER=anthropic` plus `ANTHROPIC_API_KEY`. See
[docs/AI.md](docs/AI.md). The optional knowledge base (search with citations, historical failures)
is described in [docs/RAG.md](docs/RAG.md). Change-based test selection, flaky-test analytics,
failure correlation and maintenance advice are in [docs/INTELLIGENT_QA.md](docs/INTELLIGENT_QA.md).

**AI orchestration (LangChain + LangGraph).** The optional `ai-orchestration/` service takes a
request such as *"Execute all payment regression scenarios in QA using Chromium"*, validates it
against the real tags, plans the run, **pauses for human approval**, runs it through the MCP
server (so through the same Maven/TestNG build), monitors it, and returns the framework's result
with failure analysis and recommendations. It works without any model (rules only). API, approval
rules and configuration: [docs/AI_ORCHESTRATION.md](docs/AI_ORCHESTRATION.md).

## Configuration

Resolution order, highest first: `-Dkey=value` → environment variable (`BASE_URL` for `base.url`)
→ `config/<env>.properties` → `config/default.properties`.

Secrets are **never** stored in `qa`/`staging` property files. Provide `AUTH_USERNAME`,
`AUTH_PASSWORD` and `OAUTH_CLIENT_SECRET` as environment variables (GitHub Secrets in CI).
`config/local.properties` contains only the throwaway credentials of the bundled demo app.

| Key | Default | Purpose |
|---|---|---|
| `trace.mode` | `retain-on-failure` | `off`, `on`, `retain-on-failure`, `on-first-retry` |
| `video.enabled` | `false` | Record video; attached on failure |
| `retry.count` | `0` (1 in qa/staging) | Retries; failed attempts stay visible |
| `ui.dialog.policy` | `accept` | Native dialog handling (`accept`/`dismiss`) |
| `api.max.response.ms` | `2000` | SLA used by the response-time step |

## Project layout

```
pom.xml                         versions centralised in <properties> + BOMs
testng-{smoke,regression,ui,api,e2e,unit}.xml
config/checkstyle.xml           quality gate
Dockerfile, docker-compose.yml  test-runner image; demo app + runner for local/CI parity
.github/workflows/              ci, regression, cross-browser, docker, run-suite (reusable), report (reusable)
sut/                            demo application under test (FastAPI) + its Dockerfile
scripts/                        allure-report.sh, ci_summary.py, check_versions.py
mcp-server/                     MCP server + AI layer (separate Maven project; the framework never depends on it)
rag/                            RAG service (Python FastAPI + ChromaDB), tests and retrieval gold set
ai-orchestration/               optional LangChain + LangGraph workflow service (/ai/* API); calls MCP and RAG over HTTP
src/main/java/io/qaplatform/
  core/      config · data · reporting (Allure, Json, Redactor, ApiMetrics) · listeners · execution · exceptions · assertions
  ui/        browser (BrowserPool, BrowserSession, PlaywrightFactory, AuthState) · pages · components · network · utils
  api/       client · auth (Bearer, Basic, OAuth2) · services · models · requests · responses · schemas · assertions
src/test/java/io/qaplatform/
  bdd/       context (ScenarioContext) · hooks · steps · support (parameter types, fixtures) · runners
  unit/      framework unit tests
src/test/resources/
  features/{login,payment,user,api,e2e}/ · config/ · testdata/ · schemas/
docs/                           assessment, gap analysis, plan, decisions, upgrading
```

## How a scenario runs

1. A TestNG suite selects a runner (e.g. `SmokeRunner`, tags `@smoke and not @quarantine`).
2. The runner's parallel DataProvider hands each scenario to a worker thread.
3. PicoContainer creates a fresh `ScenarioContext` and step/hook instances for that scenario.
4. Steps use page objects and API services; browser and API contexts are created lazily, so
   API-only scenarios never start a browser.
5. Each worker reuses one Playwright driver and one browser per type; every scenario gets
   its own `BrowserContext` (cookies, storage and cache isolated).
6. On failure: full-page screenshot, browser diagnostics (console errors, page errors,
   failed/5xx requests, dialogs), Playwright trace and the scenario's API-call log are attached.
7. Cleanup registered by fixtures runs in LIFO order, even after failures.
8. `ExecutionListener` writes `target/test-failures/*.json` and `target/execution-summary.json`.

## Writing tests

* **Feature files** describe behaviour by role and intent, never credentials or card data:
  `When the user signs in as the administrator`. Parameter types resolve roles to real values.
* **Steps** are thin: call a page object or service, store results in `ScenarioContext`, assert.
  One step text = one definition (Cucumber ignores the Given/When/Then keyword).
* **Page objects** expose actions and locators, not assertions. Locator priority: role → label →
  placeholder → text → test id → CSS. Reusable widgets are components (`TableComponent`,
  `ModalComponent`, `SearchBoxComponent`, `DropdownComponent`, `HeaderComponent`).
* **Test data**: create it through the API with `UserFixtures` (unique per scenario, auto-deleted).
* **Assertions**: Playwright web-first assertions for UI, `ApiAssertions` for responses,
  AssertJ for object comparisons.
* **Secrets in steps**: do not put a password parameter on an Allure `@Step` method. Allure
  writes step arguments into the raw report JSON, even in "masked" mode. Use
  `Allure.step(name, lambda)` instead (see `LoginPage.signIn`).

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Application health check failed` | Start the demo app or point `base.url`/`api.base.url` at your environment |
| `Missing configuration key: auth.password` | Export `AUTH_PASSWORD` (non-local environments) |
| `Duplicate step definitions` | Two annotations share the same text; keep one |
| Regex assertion fails although text looks right | Playwright evaluates regex in JavaScript: avoid Java-only syntax such as `\Q…\E` |
| `mvn allure:report` produces no report | The project path contains spaces; use `scripts/allure-report.sh` |
| Locator by label times out on a `<select>` | Labels wrapping a select include the selected option in the accessible name; use `for`/`id` labelling in the app |
| Trace viewer | `npx playwright show-trace target/artifacts/traces/<file>.zip` |
| Container run fails with `HTTP 401` on login | An empty `SUT_ADMIN_PASSWORD` used to override the demo password; fixed (blank now means "use the default"). Rebuild the `sut` image |
| CI job summary is empty | The run failed before tests started (compile, browser install, demo app health check): open the job log |
| Logs per scenario | `target/logs/execution.log` (thread and scenario name on every line) |
