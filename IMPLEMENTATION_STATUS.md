# Implementation Status

Last updated: 2026-11-20 · Environment: macOS, JDK 25 (compiling with `--release 21`), Maven 3.9.14,
Playwright 1.65.0, demo app on `http://localhost:8000`.

## Phases

| Phase | Status |
|---|---|
| 0 Assessment, gap analysis, plan | ✅ Done (`docs/CURRENT_ARCHITECTURE.md`, `docs/GAP_ANALYSIS.md`, `docs/IMPLEMENTATION_PLAN.md`) |
| 1 Core Java + Cucumber/TestNG BDD | ✅ Done and verified (below) |
| 2 GitHub Actions + test Docker image | ✅ Done; Docker verified locally, workflows linted and their steps run locally, **not yet run on GitHub** (below) |
| 3 MCP server | ✅ Done and verified with two independent MCP clients (below); Docker image for it is part of Phase 7 |
| 4 LLM integration | ✅ Built and unit/integration-tested; live model verified only partly (local llama3, too slow on this machine; no cloud key available) |
| 5 RAG service | ✅ Done; verified with real ONNX embeddings and through MCP (no cloud LLM answer yet) |
| 6 Intelligent QA | ✅ Done; verified on real runs (imported CI-like results + real MCP runs) |
| 7 Production readiness | ✅ Done; platform verified in Docker; security and performance validation documented |
| Update: LangChain + LangGraph (`ai-orchestration/`) | ✅ Done (phases 4b–7b, below); verified against the live MCP server, RAG service and Java framework **without a model** (rules path). The LLM path is tested with fake models and a stubbed Anthropic endpoint only, **not with a live model** |

## Update: notifications and test-management export (2026-11-24)

| Check | Result |
|---|---|
| `python3 -m unittest discover -s scripts/tests` | 14/14 (fake HTTP; no live Slack/Teams/Xray/TestRail account) |
| Dry runs on the real regression output | expected payloads; 46 untagged scenarios listed |
| actionlint 1.7.12 on all workflows | clean |

## Phase 1 verification log (actual results)

| Run | Result |
|---|---|
| `mvn test-compile` (Checkstyle + javac) | BUILD SUCCESS, 0 Checkstyle violations |
| `-DsuiteXmlFile=testng-unit.xml` | 15/15 passed |
| `-DsuiteXmlFile=testng-smoke.xml` | 4/4 passed |
| `-DsuiteXmlFile=testng-regression.xml` (chromium) | 37/37 passed, 18.3 s, 4 threads |
| `-DsuiteXmlFile=testng-regression.xml -Dbrowser=firefox` | 37/37 passed, 36.1 s |
| `-DsuiteXmlFile=testng-regression.xml -Dbrowser=webkit` | 37/37 passed, 28.3 s |
| `-DsuiteXmlFile=testng-ui.xml` | 15/15 passed |
| `-DsuiteXmlFile=testng-api.xml` | 20/20 passed, **0 browsers launched** |
| `-DsuiteXmlFile=testng-e2e.xml` | 2/2 passed |
| `-Dcucumber.filter.tags="@smoke and @api"` | 2/2 passed, 0 browsers launched |
| `-Dcucumber.filter.tags="@payment and @regression and not @quarantine"` | 10/10 passed |
| `-Dthreads=2` on regression | 37/37 passed on exactly 2 worker threads, 2 browsers reused |
| `-Dshard.index={0,1,2} -Dshard.total=3` on API suite | 9 + 4 + 7 = 20 scenarios, no overlap |
| Forced failure (`-Dauth.password=deliberately-wrong -Dretry.count=1 -Dtrace.mode=on-first-retry`) | Failed as expected; 2 failure records (original + retry), trace saved for retry only, screenshot + diagnostics + API log attached |
| Secrets scan of `target/` after runs | No passwords, client secret or generated customer passwords in Allure, Cucumber, logs or failure records |
| Allure report (`scripts/allure-report.sh`) | Generated; 37 tests (one entry per scenario, no TestNG duplicates) |
| Checkstyle probe (JUnit import, `Thread.sleep`, empty catch) | All three rejected; build passes after removal |

**Performance note:** against the fast local demo app, 4 threads (18.3 s) and 2 threads
(18.8 s) took about the same time, because the run is dominated by browser start-up and fixed
UI steps. No speed-up is claimed until it is measured against a real environment.

## Phase 2 verification log (actual results)

| Check | Result |
|---|---|
| Review fix: `TestDataManager` raises `FrameworkException` for missing *and* malformed files | 3 new unit tests; unit suite 18/18 |
| `docker compose --profile tests build` (cold) | Both images built in ~90 s; the test image compiles the code (Checkstyle included) at build time |
| `docker compose run --rm tests` (smoke, in Docker) | 4/4 passed |
| Regression in Docker, `-Dbrowser=chromium` / `firefox` / `webkit` | 37/37, 37/37, 37/37 (10 s, 29 s, 22 s) |
| Results written to host `./target` from the container | Yes (Allure results, summary, failure records, logs) |
| First container run | Failed 4/4 with HTTP 401: compose passed an *empty* `SUT_ADMIN_PASSWORD` and the demo app used it. Fixed in `sut/app.py` (blank = default); rerun passed |
| actionlint 1.7.12 (with shellcheck) on all 6 workflows | 0 findings. A probe copy with a mistyped reusable-workflow input, an untrusted `${{ github.event.* }}` in `run:` and an unquoted variable was flagged on all three, so the checks are active |
| Workflow shell steps executed locally from the YAML (bash `-eo pipefail`) | Input validation accepts valid values and rejects `testng-api.xml;id` and shard `3/3`; test step ran the API suite with `@api and not @quarantine`, shard 2/3 → 4/4; job summary rendered; shard planner gives `[0,1,2]` / `[0..5]`; report step builds the Allure report, and skips with a warning when there are no results |
| `mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"` (CI browser install) | Exit 0 (`--with-deps` is Linux-only and runs only on the GitHub runner) |

### Review follow-ups (2026-11-19)

| Change | Verification |
|---|---|
| Demo-app runs no longer create deployments (`environment.deployment: false` for `local`; qa/staging still recorded) | actionlint + GitHub workflow schema pass; probe confirms actionlint validates the `deployment` key |
| Schedules in IST: regression 07:30 Mon–Fri, cross-browser 09:30 Sun (`timezone: Asia/Kolkata`) | actionlint + schema pass; probe confirms actionlint validates the `timezone` key |
| Scheduled runs target `vars.SCHEDULED_TEST_ENV` (default `local`) | actionlint pass; the value goes through run-suite's allow-list validation |
| `.github/dependabot.yml` (actions, Maven, pip, demo-app image; groups, 7/30-day cooldown) | Valid against SchemaStore `dependabot-2.0` schema (check-jsonschema 0.39.0) |
| `scripts/check_versions.py` + first step of the CI build job | Passes on 1.65.0/1.65.0; with the Dockerfile set to 1.64.0 it fails with the exact `FROM` line to use |

**Not verified:** the workflows have not run on GitHub (this project is not yet a GitHub
repository). Unverified until the first run: artifact upload/download and merge across shards,
Playwright browser cache, `--with-deps` on Ubuntu 24.04, GitHub Environments secret injection,
the scheduled triggers and their time zone, Dependabot's first PRs (including updating
SHA-pinned actions and their version comments), and `docker.yml` on a Linux runner (file ownership of the mounted
`target/`).

## Phase 3 verification log (actual results)

| Check | Result |
|---|---|
| `mvn -f mcp-server/pom.xml package` (Checkstyle + tests + shaded jar) | BUILD SUCCESS, 0 Checkstyle violations, **34/34 tests** |
| Discovery vs framework (real feature files) | Test-case counts equal the framework's suites: regression 37, API 20, UI 15, E2E 2, smoke 4; 0 Gherkin parse errors; **every step in every feature binds to exactly one step definition** (Cucumber expressions + the 3 custom parameter types) |
| Execution lifecycle (stand-in build) | PASSED; FAILED when any scenario fails, whatever the exit code; ERROR for a build failure without a summary, **and for a non-zero exit with no failures (never PASSED)**; NO_TESTS; TIMED_OUT stops the process tree (2 s limit, done in under 20 s); cancel by requester only; bounded queue; history survives a restart, and unfinished runs become ERROR |
| Approvals | Staging run → AWAITING_APPROVAL; same person as admin → refused; different admin → runs; rejected run never starts |
| Gate and validation | Viewer calling `run_*` → `forbidden` and audited; unknown arguments, `@api; rm -rf /`, `@api -DskipTests`, `prod`, `../pom.xml`, path-like ids and `http://169.254.169.254/…` all rejected |
| HTTP transport (in-process Jetty + the SDK's HTTP client) | No/invalid token → 401 with `WWW-Authenticate`, audited; `/health` open; viewer can discover but not run; runner submits a staging run, the runner's own approval is refused, the admin approves, the run completes PASSED |
| **stdio end to end, Java SDK client** (`StdioFrameworkIT`) | Client launches the jar; 34 tools; `list_scenarios` → 20 API test cases; `run_api_tests` runs the **real framework**: PASSED 20/20; summary, Cucumber report and history read back through MCP |
| **stdio end to end, Python SDK client** (official `mcp` 2.3.0, a separate implementation) | Identity `runner`; tags listed; duplicate check found 1 candidate (two `user_api.feature` outlines with identical steps); `run_smoke_tests` (UI + API, Chromium) → PASSED 4/4 |
| Failure evidence (forced login failure in the workspace, then Python client) | Failure record + failing Cucumber step; `get_screenshot` → PNG (18 KB); `get_trace_information` → trace zip (14 entries) + viewer command; `get_scenario_logs` → that scenario's log lines |
| Browser tools (Python client) | Page information and accessibility snapshot of `/login`; page screenshot returned; `https://example.org/` → forbidden |
| Secret scan of the MCP state (audit log, records, console logs, copied results) | No passwords, client secret, forced wrong password or MCP tokens |
| API suite without browsers (`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`, empty browser dir) | 20/20, nothing downloaded; used in CI for API/unit suites and the MCP job |
| CI job `mcp-server`, Dependabot entry, extended version check | actionlint 0 findings; Dependabot schema valid; version check catches an `mcp-server/pom.xml` Playwright mismatch |

**Bugs found during verification, all fixed:** stacked `@Given` annotations on one method were
indexed once (fixed; test added); the redactor masked execution ids such as
`run-20261119-170803-2965f6` as card numbers (card masking now requires a card layout and a
Luhn-valid number; test added); screenshots and traces attached by the framework's `@After` hook
live in Allure *container* files and were not found (fixed; test added); my descriptions of
`run_api_tests`/`run_ui_tests` omitted that the runners exclude `@e2e` (fixed).

**Not verified:** the `mcp-server` CI job has not run on GitHub; a real Claude Code or Claude
Desktop session has not been connected (two SDK clients were used instead); HTTP behind a TLS
proxy.

## Phase 4 verification log (actual results)

| Check | Result |
|---|---|
| `mvn -f mcp-server/pom.xml clean package` | BUILD SUCCESS, **74/74 tests**, 0 Checkstyle violations; framework unit tests still 18/18 |
| Provider wire formats (`LlmProvidersTest`, local stub servers) | OpenAI-compatible (`max_tokens`/`max_completion_tokens`, JSON mode, bearer only when a key exists), Gemini (key in header not URL, system instruction), **Anthropic through the official SDK** (streamed SSE accumulated; usage, stop reason; no thinking parameter for claude-opus-5-5); HTTP errors typed and redacted; missing key/model → AI disabled with a reason |
| Prompt safety and client (`AiClientTest`) | `<data>` fences cannot be closed from inside; secrets redacted before the provider sees them; one JSON repair round; usage log has no prompt text; daily budget enforced |
| Rule-based failure classification (`RuleClassifierTest`) | 13 realistic signatures map to the right of the 11 categories, each with matched evidence; known-flaky lowers confidence |
| Generation (`GenerationTest`, on a **copy** of the framework) | All 6 existing feature files pass the validator; parse errors, undefined steps, tag issues, duplicates and literal card numbers caught. Scripted flow: invalid first draft → repaired; step class with a hard sleep → repaired; the class **compiles against the real framework sources** and binds the new step; admin acceptance creates the two files; second acceptance refused; wrong method name, new ambiguity and Selenium import all rejected |
| Planning and advice (`PlanningAndAdviceTest`) | The spec's example "all payment regression scenarios in QA using Chromium" → `@payment and @regression`, QA, 10 test cases (the framework ran the same 10 in Phase 1), "27 not included"; full regression, quoted scenario names, production → clarification, vague → clarification; an AI plan with an unknown tag is rejected; a valid AI plan runs through the execution service; changed `PaymentPage.java` → only payment UI scenarios; core file → full regression; docs → no impact; `../` paths rejected; OpenAPI coverage found endpoints with no API automation; failure analysis verifies cited evidence (a made-up quote is flagged) |
| Viewer / no-model behaviour (`GuardedToolsTest`) | AI off by default; viewers get deterministic plans with a note; generation needs runner, acceptance needs admin |
| **Live: local llama3 (Ollama, 8B Q4) through MCP, Python client** | 48 tools listed. 1 of 5 model calls succeeded: regression advice in 279 s (suggestion `@ui` validated, kept separate). 4 calls hit the 300 s timeout, and **each tool still returned its deterministic result**. A keyword-built plan ("user API checks locally with Firefox") executed the real framework: **13/13 passed**. OpenAPI coverage found `POST /api/oauth/token`, `GET /api/me`, `POST /api/files`, `GET /api/reports/users.csv` with no API automation |

**Not verified:**
* No cloud model was called: no Anthropic, OpenAI or Gemini key was available, so those adapters are proven only against stub servers that use their wire formats.
* The quality of real model output for failure analysis, feature generation and step generation is unknown: llama3 on this 8 GB machine took 78 s for a two-word reply and timed out on full prompts.
* The stdio end-to-end test (`StdioFrameworkIT`, now expecting 48 tools) was not re-run after Phase 4; the Python client listed 48 tools and ran a real suite through the same jar.

## Phase 5 verification log (actual results)

| Check | Result |
|---|---|
| `pytest rag/tests` (hash embedder) | **12/12**: chunking per type (feature per scenario with Background, Java per method with step expressions, Markdown sections ignoring code blocks, Cucumber/failure/OpenAPI/TestNG/log), redaction of secrets/cards/e-mail/phone (example domains and run ids kept), injection flags, 401 without key, reader cannot ingest/delete, restricted bug report invisible to internal readers, incremental ingest (unchanged / version 2 / history), duplicate chunks reported, delete removes from search, paths outside the roots rejected, stored text redacted, filters, citations, fenced context, unknown filter rejected, gold-set recall@5 ≥ 0.8 |
| Real embeddings (ONNX all-MiniLM-L6-v2) on the project: 32 documents, 207 chunks, 13 s | 15-query gold set: semantic recall@5 1.0 / MRR 0.85; keyword 1.0 / 0.917; **hybrid 1.0 / 0.867**. Hybrid did not beat keyword on ranking here; the set is small and favours exact project terms |
| RAG server: health, no key | `UP`, 207 chunks, `onnx/all-MiniLM-L6-v2`; search without key → 401 |
| Through MCP (Python client → packaged MCP server → RAG server) | 52 tools; `ingest_project_knowledge` incremental (32 unchanged, 8 new; the test card number in `PaymentApiSteps` masked); `search_knowledge` "declined card" → the right scenarios; `java_kind=page_object` filter → only `PaymentPage`; `ask_knowledge_base` without AI → sources; `ingest_execution_results` on a real login failure; `analyze_scenario_failure` → rules `AUTHENTICATION_FAILURE` 0.8 + **knowledge-base matches: that run's indexed failure record and scenario result** |
| CI job `rag`, Dependabot `/rag` | actionlint 0 findings; Dependabot schema valid |

**Bugs found and fixed:** the citation fence turned `[1]` into `_1_` (fixed; tested); Java's
default HTTP/2 client sent an `h2c` upgrade that uvicorn rejects for POSTs (MCP→RAG calls failed
with 400; now HTTP/1.1 for local services); README headings inside code blocks became sections
(fixed; test added).

**Not verified:** an LLM-generated answer from `/rag/query` or `ask_knowledge_base` (no cloud key;
local llama3 too slow); a PDF document (the PDF code path has no test with a real PDF); Ollama or
OpenAI embedders. (MCP Java tests after the RAG wiring: 74/74 pass.)

## Phase 6 verification log (actual results)

| Check | Result |
|---|---|
| `InsightsTest` (imported runs in the framework's real formats + the real project) | **6/6**: imported runs get the framework's status; `/etc` refused; flaky classes FLAKY `pfpff` (flips) and `pFppf` (retry-pass), CONSISTENTLY_FAILING `ppfff`, RECOVERED `fpppp`, STABLE; failures clustered (timeouts → TIMING, recurring, only on WebKit; an outage hitting 3 scenarios in one run → ENVIRONMENT + shared-cause hint); escaped failure detected by the run comparison; `git diff` read in a temp repo, `--output=…` and `HEAD;rm` refs rejected, non-repo refused; maintenance findings on the real project; requirement coverage (declined/refund covered, currency rule not, "sign out" → *Administrator signs out*) |
| Real end to end (Python MCP client, packaged server, demo app) | 57 tools. 3 real login-smoke runs (wrong / correct / wrong password) imported as CI results (FAILED, PASSED, FAILED); real `@payment` selection through MCP 12/12 and real full regression 37/37; comparison: no escapes (the full run had no failures); `get_flaky_tests`: *Administrator signs in* FLAKY `fpfp` (3 flips), 12 others STABLE; `correlate_failures`: one AUTHENTICATION_FAILURE cluster, recurring in 2 runs, only on chromium; maintenance: the 2 near-duplicate user-API outlines + the flaky scenario; `suggest_regression_tests` for `DashboardPage.java` → 10 of 37 test cases (login + user-management UI); requirement coverage flagged "account locked after five failed attempts" as a gap |

**Bugs found on real data and fixed:** history kept only the first line of a failure message, so
the login failure was classified APPLICATION_DEFECT instead of AUTHENTICATION_FAILURE (now the first
6 lines; re-verified); requirement matching chose the wrong scenario for "The user can sign out"
(now light stemming and a tie-break on the scenario name; test added).

**Not verified:** `changed_since` against this project (it is not a Git repository; tested in a temporary
repository); an escaped failure on real runs (the real full regression had no failures; escape
detection is covered by the unit test); AI maintenance plans with a real model.

## Phase 7 verification log (actual results)

| Check | Result |
|---|---|
| Images (MCP, RAG, demo app), cold build | 3 min; `docker compose --profile platform up --wait`: chroma, rag, mcp, sut all healthy in 18 s |
| Platform over HTTP with generated tokens (Python MCP client) | No token → 401; viewer: discovery ok, `run_smoke_tests` forbidden; runner: `ingest_project_knowledge` into the ChromaDB container (46 docs, 285 chunks), search finds the right scenario, **API suite inside the MCP container 20/20**; staging run awaiting approval, runner cannot approve, admin rejects; `/metrics` and `/rag/metrics` show the calls, auth failure and run outcomes |
| Persistence | After `down` + `up`: index still 285 chunks, run history kept |
| SpotBugs (effort max, medium+) | Framework 3 → 0; MCP 26 → 0 (all fixed; only `EI_EXPOSE_REP*` excluded, with a reason) |
| Trivy (deps, secrets, Docker/Compose config; HIGH/CRITICAL) | MCP Jackson 2.19.4 (8 HIGH) fixed by pinning 2.23.0 → 0; chromadb 4 CVEs without a fix: accepted with mitigations; 0 secrets; 0 misconfigurations |
| Performance (docs/PERFORMANCE.md) | Regression 1 vs 4 threads on this 8 GB machine: 30.5 s vs 28.0 s (contention: per-scenario time 0.8 → 2.8 s); tracing ≈ 3 %; RAG embedding CPU-bound (64 chunks: 34 s @1.5 CPU, 11 s @4); search p50 keyword 5 ms, hybrid 83 ms |
| MCP tests after all fixes | 80/80; Anthropic SDK streaming test passes with Jackson 2.23.0 |
| CI | New `security` job (Trivy, `--ignore-unfixed`) and SpotBugs steps; actionlint 0 findings; CI-equivalent Trivy run exit 0 |

**Not verified:** the new CI jobs on GitHub; a TLS reverse proxy in front of MCP/RAG; Docker on
Linux hosts (only Docker Desktop on macOS).

## LangChain + LangGraph update verification log (actual results, 2026-11-20)

| Check | Result |
|---|---|
| Unit + API tests (`ai-orchestration`, fakes for MCP, RAG and the model) | **24 passed**, 3 skipped (the live-stack tests, opt-in) in 3.8 s |
| Integration tests against the live Java MCP server (HTTP + runner token) and RAG service | **4/4 passed**: the 12 required MCP tools load as LangChain `StructuredTool`s; `@payment` = 12 test cases, payment regression = 10; invalid argument and unknown tool rejected; wrong token rejected; RAG retriever returns cited documents; unreachable RAG reported |
| Structured output through `ChatAnthropic` (stubbed `/v1/messages`) | Native `output_config.format` json_schema used (no forced tool call); token usage recorded; schemas use only keywords Claude accepts |
| Real run, happy path: *"Execute all payment regression scenarios locally using Chromium"* via `POST /ai/execute` | Plan `@regression and @payment`, 10 test cases, 27 regression test cases reported as not included; paused at `AWAITING_APPROVAL` with **nothing run**; approved by a second key; MCP ran `mvn test` (filter `(@regression and @payment) and not @quarantine`): **10/10 passed in 19 s**, `test_result: PASSED` from the framework; Allure results, RAG citations, rule recommendation; 33 checkpoints written |
| Real run, failure path (demo app restarted with a different admin password) | `@smoke and @login` → approved → **1/1 failed**, `test_result: FAILED` from the framework; failure analysis `AUTHENTICATION_FAILURE` (rules, 0.8) with the real Playwright evidence (expected `/dashboard`, received `/login`); `POST /ai/failure-analysis` on the same run: same result in 0.23 s. Demo app restored afterwards |
| Docker image `qa-platform-ai:local` | Built in 41 s, 456 MB, non-root; container against the live MCP server: staging plan needs `different_approver`, requester's approval → 403, reject → `REJECTED`, nothing executed |
| Secrets | No API keys, MCP tokens or RAG keys in the audit log, data files or container logs (searched for each real key) |
| Full Docker platform (`docker compose --profile platform up --build --wait`) | chroma, rag, mcp, sut, **ai** all healthy in 29 s; through the `ai` container: payment regression paused for approval, approved by a second key, MCP ran it inside its container: **10/10 passed in 17 s** (`PASSED` from the framework), RAG citations from the ChromaDB container; the MCP audit log records every call under the AI service's own identity `ai-orchestrator` (runner) |
| Checkpoint across a container restart | `@smoke and @api` workflow `AWAITING_APPROVAL` → `docker compose restart ai` → still `AWAITING_APPROVAL` with the same plan → approved → **2/2 passed** |
| Memory in Docker during the run | ai 88 MiB (limit 768 MiB), mcp 321 MiB, rag 202 MiB, sut 36 MiB, chroma 24 MiB |
| Live model: OpenAI `gpt-4o-mini` in Docker (2026-11-21; cap 100k tokens/day) | **Verified.** Vague request *"…confidence that refunds and card charges still behave - kick off those checks on our local box"* → model: `@payment and @regression`, local, chromium (keyword rules alone had returned UNSUPPORTED) → validated against real tags → approval → **10/10 passed**. Forced login failure (wrong admin password) → **FAILED** from the framework → model analysis `AUTHENTICATION_FAILURE`, agrees with the rules, confidence 0.8, all 3 quoted evidence lines verified against the Playwright evidence. Total: 9 model calls, 6,568 tokens, **≈ $0.0016**. An earlier key without credits returned 429 `insufficient_quota`; the workflow used the rules and labelled it |
| Bugs found by the live model, fixed (26 tests) | 1) The model picked `chrome` when no browser was named (not installed in the MCP image) → the browser now comes only from the request (default `chromium`); a different model choice is overridden and labelled; prompt `intent v2`. 2) A failure before the first step is `skipped` in Cucumber, so nothing was analysed → result collection and `/ai/failure-analysis` fall back to the framework's failure records. 3) The model wrapped quoted evidence in extra quotes, so real evidence counted as unverified (confidence capped) → quotes are compared without wrapping quotes, against plain text instead of escaped JSON |
| Review of `ai-orchestration/` (2026-11-21) | 10 findings, 8 reproduced by probes. 9 fixed with a regression test each (`tests/test_review_fixes.py`): environment pinned to the request; model calls off the event loop; monitoring errors counted in a row, polls paced, step limit from the deadline; evidence checked against this run only; unverified model category no longer drives rule advice; tokens of invalid replies counted; lost run starts recovered from MCP history; named scenarios looked up exactly. 10th (self-approval outside staging) decided: **every execution now needs a second person** (an approver who is not the requester) |
| Live re-check after the review fixes (2026-11-21, platform from the new project folder, `gpt-4o-mini`) | Self-approval refused for an operator (403) and for an approver (403); the requester can still reject; a second person's approval runs it. Request naming no environment: the model chose `qa`, the plan used `local` and recorded the override; **12/12 @payment passed**. Named scenario found by exact lookup (`get_scenario_details`) → **1/1 passed**. `get_execution_history` matches a started run for the lost-start recovery. Forced login failure → **FAILED** from the framework; model `AUTHENTICATION_FAILURE` 0.8 with 3/3 quotes verified; rule recommendation kept. 8 model calls, 6,451 tokens, ≈ $0.0016 |
| GitHub | Private repository created and pushed; first CI run on GitHub: **all 6 jobs passed** (compile/Checkstyle/unit, smoke chromium, MCP server, RAG, AI orchestration, security scan) |
| Compose / CI / Dependabot | `docker compose --profile platform config` valid with the new `ai` service, secrets and volume; YAML of CI and Dependabot parses; `scripts/dev-secrets.sh` run in a scratch copy: AI keys and the new `ai-orchestrator` MCP runner token match their hashes |

**Not verified:** a live model (Claude, OpenAI, Gemini or local) driving the LangChain calls; the new
CI job on GitHub.

## Acceptance criteria

| ID | Criterion | Status | Evidence |
|---|---|---|---|
| AC01 | UI automation works | ✅ | UI suite 15/15 |
| AC02 | API automation works | ✅ | API suite 20/20 |
| AC03 | Hybrid UI + API works | ✅ | E2E suite 2/2 |
| AC04 | Feature files execute | ✅ | All suites |
| AC05 | Step definitions reusable | ✅ | `ApiResponseSteps` shared by user + payment APIs; Cucumber duplicate detection hit and fixed |
| AC06 | TestNG is the Cucumber runner | ✅ | `BaseCucumberRunner extends AbstractTestNGCucumberTests`; no JUnit (enforced by Checkstyle) |
| AC07 | Parallel execution works | ✅ | `-Dthreads=2` → 2 workers; default 4 workers |
| AC08 | TestNG XML suites execute | ✅ | 6 suites run |
| AC09 | Tag filtering works | ✅ | Tag-expression runs above |
| AC10 | Browser isolation | ✅ | Context per scenario; concurrent scenarios with different users and storage pass in parallel |
| AC11 | No shared scenario context | ✅ | PicoContainer scenario scope; no static mutable state outside thread-confined pools |
| AC12 | Allure report generated | ✅ | `scripts/allure-report.sh` |
| AC13 | Screenshots on failure | ✅ | Forced-failure run |
| AC14 | Playwright traces available | ✅ | `target/artifacts/traces/*.zip`, attached to Allure |
| AC15–AC17 | GitHub Actions smoke / regression / cross-browser | 🟡 | **AC15 ✅** on GitHub (2026-11-21, first push): CI run 44855072842 passed all 6 jobs, including smoke in Chromium. AC16 (scheduled sharded regression) and AC17 (weekly cross-browser) have not run on GitHub yet |
| AC18 | MCP server exposes tools | ✅ | 34 tools listed by the Java and Python SDK clients, roles in descriptions |
| AC19 | MCP discovers Cucumber scenarios | ✅ | Counts equal the framework suites; every step bound to its definition |
| AC20 | MCP invokes approved Java execution | ✅ | Real API suite (20/20) and smoke (4/4) run through MCP; staging needs a different admin |
| AC21 | LLM interprets natural-language requests | 🟡 | Validation + fallback verified, a scripted model plan executed; live llama3 timed out, so rules were used |
| AC22 | AI generates valid feature files | 🟡 | Full pipeline verified with a scripted model (validate, repair, compile, accept); no live model output yet |
| AC23 | AI failure analysis retrieves evidence | 🟡 | Evidence collection + rule classification + evidence verification tested; live model opinion not yet observed |
| AC27 | AI suggests missing scenarios | 🟡 | Deterministic gaps verified live (OpenAPI endpoints, requirement coverage); AI ideas tested with a scripted model only |
| AC24 | RAG ingests feature files | ✅ | One chunk per scenario with tags/module; 32 project documents indexed |
| AC25 | RAG ingests historical execution knowledge | ✅ | Failure records, Cucumber results and summary of a real run indexed via `ingest_execution_results` |
| AC26 | RAG retrieves relevant failures | ✅ | `analyze_scenario_failure` returned the indexed failure of the same scenario as top knowledge matches |
| AC28 | Docker services start correctly | ✅ | All five services (sut, tests, chroma, rag, mcp) healthy; platform exercised over HTTP; state persists across restarts |
| AC29 | Core works without AI | ✅ | No AI dependency on the classpath |
| AC30 | Security requirements are verified | ✅ | docs/SECURITY_VALIDATION.md: scans (Trivy, SpotBugs, Checkstyle, actionlint) and live behavioural checks; one accepted, mitigated risk (chromadb, no fix released) |
| AC31 | Documentation is complete | ✅ | All 36 deliverables mapped in the README guide index (architecture, setup, execution, CI, MCP, LLM, RAG, troubleshooting, runbooks, onboarding, deployment, validation) |
| AC32 | All critical functionality is executed and verified | 🟡 | Everything ran for real except live cloud-model output (AC21–23, AC27 remain 🟡 until a Claude key is used) and the GitHub-hosted CI runs (AC15–17) |

## Known issues / decisions to revisit

* Dependency upgrades held back on purpose (Dependabot ignore rules, 2026-11-21): **Surefire 3.6+** (runs TestNG on
  the JUnit Platform and drops `suiteXmlFiles`: breaks the suites and the no-JUnit rule) and **MCP Python SDK 2.x**
  (cannot be installed with langchain-mcp-adapters). Cucumber 8 and Allure 3 were migrated on 2026-11-21 (docs/UPGRADING.md).
* `BrowserSession.close` asks for the video path even when video is off, which logs "Video recording has not been
  started" on every failed UI scenario. Evidence is still attached and the context still closed; harmless but noisy.
* `mvn allure:report` mis-quotes the output path when the project path contains spaces
  (it wrote to a literal `"…/allure-maven-plugin"` directory here). `scripts/allure-report.sh`
  calls the pinned CLI directly and works for any path; CI paths have no spaces.
* GitHub lists an empty `local` environment after the first run (it holds no secrets and records
  no deployments). Removing it entirely would need a second copy of the test job.
* Playwright upgrades need one manual edit (the `Dockerfile` `FROM` tag) in the Dependabot PR;
  CI says exactly what to change.
* Playwright traces in `failure-evidence-*` artifacts can contain request headers: uploaded only
  on failure, 7-day retention, visible to repository readers only.
* MCP: runs execute one at a time (they share `target/`); a manual `mvn test` in the same checkout
  at the same time would mix results.
* MCP: scenario outlines whose names contain `<placeholders>` cannot be run by name; use a tag.
* MCP: browser tools never sign in, so pages behind login show the sign-in page.
* MCP: the shaded jar is about 245 MB, mostly Playwright's driver for every OS (needed by the
  browser tools); a slimmer build without browser tools is possible later.
* AI: Ollama's default 4,096-token context is too small for generation prompts; set
  `OLLAMA_CONTEXT_LENGTH` and `QA_AI_CONTEXT_TOKENS` (see docs/AI.md).
* AI: the compile check compiles the whole framework in memory (a few seconds, ~1 GB heap).
* Single Maven module for the framework (ADR-001); the MCP server is a separate Maven project.
* chromadb 1.5.9 has unfixed CVEs: mitigated (no exposed port, embedded mode locally); see docs/SECURITY_VALIDATION.md.
* Parallel threads give little speed-up on 8 GB machines; scale with CI shards (docs/PERFORMANCE.md).
* AI orchestration: a workflow interrupted mid-run by a service restart shows `PAUSED`; its MCP run
  continues and is visible through MCP, but the workflow is not automatically resumed.
* AI orchestration: model-written recommendations from `gpt-4o-mini` are often generic (e.g. "investigate failure causes"
  after a passing run). They are labelled `source: llm`, and only kept when they cite real facts, but they add little;
  the rule recommendations carry the actionable advice.
* AI orchestration: the `allure.results_directory` in the final response is the MCP server's path
  (relative to its workspace), as MCP reports it.
* Bugs found in the demo app during verification, both fixed: the shared script loaded after
  the page scripts (caught by the framework's page-error capture), and a `<select>` labelled by
  wrapping (its accessible name included the selected option).
