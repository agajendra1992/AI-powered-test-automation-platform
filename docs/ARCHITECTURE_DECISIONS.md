# Architecture Decisions

## ADR-001 Single Maven module, packages per layer
**Context:** The spec suggests separate modules (`automation-core`, `ui-automation`, …).
**Decision:** One module; packages `core`, `ui`, `api` (main) and `bdd` (test) mirror those modules.
**Why:** Everything versions and ships together, nothing else consumes the layers yet, and empty or
thin modules are explicitly discouraged. Splitting later is a mechanical move.

## ADR-002 TestNG drives Cucumber; parallelism only via the DataProvider
`BaseCucumberRunner extends AbstractTestNGCucumberTests` with `@DataProvider(parallel = true)`.
Suites set `data-provider-thread-count` and never `parallel="..."`, so there is exactly one
parallelism mechanism. Maven's `-Dthreads` maps to `dataproviderthreadcount` and overrides the suite.

## ADR-003 Worker-scoped browsers, scenario-scoped contexts
Launching a browser costs about 4 s (measured), while a context costs milliseconds. `BrowserPool` keeps one
Playwright driver and one browser per type per worker thread; `BrowserSession` gives each scenario
a fresh `BrowserContext`. Pools are `ThreadLocal` (thread-confined), closed once by the runner.
API contexts reuse the same per-worker driver.

## ADR-004 Lazy resources in a PicoContainer `ScenarioContext`
Hooks never decide what to start. The first `page()` call opens a browser, so API-only scenarios
start none (verified: 0 launches for the API suite). Tag hooks only add tag-specific evidence.

## ADR-005 One reporting adapter
Only `allure-cucumber8-jvm` (Cucumber 7 used `allure-cucumber7-jvm`) is on the classpath. Adding `allure-testng` would report every
scenario twice (once as `runScenario`). Core code uses `allure-java-commons` (steps, attachments)
only, so reporting can be swapped for another backend.

## ADR-006 Machine-readable outputs as the integration contract
`target/test-failures/*.json` and `target/execution-summary.json` are the stable interface for CI,
MCP, LLM failure analysis and dashboards. AI layers read these files and never sit in the
execution path, so they cannot change a result.

## ADR-007 Secrets never reach artifacts
`Redactor` masks auth headers, password/token/secret/CVV fields and card numbers in every API
attachment and failure record. Methods that receive secrets do not use `@Step` (Allure writes
step arguments into the raw JSON even in "masked" mode). Verified by scanning `target/` after runs.

## ADR-008 Bundled demo application
A small FastAPI app (`sut/`) makes every scenario deterministic and runnable offline and in CI.
Real environments are selected with `-Denv=qa|staging`; their URLs live in properties and their
credentials in environment variables.

## ADR-009 CI as one reusable job, called by thin workflows
`run-suite.yml` (one suite × one browser × one shard) holds every step: input validation, demo
app start-up, browser install, test run, job summary, artifacts. `ci.yml` (PR smoke),
`regression.yml` (nightly, sharded) and `cross-browser.yml` (weekly, browser matrix) only choose
inputs, and `report.yml` merges every shard's Allure results into one report. A change to how
tests run is made once.

Security choices: `permissions: contents: read` everywhere; third-party actions pinned to commit
SHAs; checkout without persisted credentials; all inputs reach shell through environment
variables (never `${{ }}` inside `run:`) and are validated against allow-lists; qa/staging
secrets live in GitHub Environments of the same name, so protection rules (e.g. required
reviewers for staging) apply. qa/staging runs are recorded as deployments; demo-app runs set
`deployment: false` so PR timelines carry no deployment noise. Failure evidence (traces can include request headers) is uploaded
only on failure and kept 7 days.

## ADR-010 Containers for parity, not as the default CI path
The test image extends the official Playwright Java image (browsers and OS libraries included),
pre-resolves Maven dependencies, and compiles at build time so broken code fails the image
build. Regular CI runs on the GitHub runner with cached browsers, which is simpler to debug;
`docker.yml` proves the container path whenever the Docker setup changes. `docker-compose.yml`
gives the same demo app + runner pair locally.

## ADR-011 Dependabot for updates, a version check for the one coupled pair
Dependabot keeps SHA-pinned actions, Maven, pip and the demo-app image current with grouped
weekly PRs and a 7-day cooldown, and every PR passes through normal CI. The Playwright Maven
version and the test image tag must move together, but Dependabot's cross-ecosystem grouping
documents neither how other Maven updates are handled once a Maven entry joins a group nor the
pattern form for an `mcr.microsoft.com` image. Instead of depending on that,
`scripts/check_versions.py` runs first in CI and fails a mismatched PR with the exact
`FROM` line to use. Cost: one manual edit per Playwright upgrade.

## ADR-012 MCP server: a separate Java module that drives the framework's own build
The MCP server uses the official Java MCP SDK (2.2.0) and lives in its own Maven project, so the
framework gains no MCP or AI dependency (AC29). It never runs tests itself: each run is
`mvn clean test -DsuiteXmlFile=…` with arguments from allow-lists, started without a shell. This
keeps one execution engine and lets MCP runs produce exactly the reports CI produces.
Discovery reads sources with Cucumber's own Gherkin parser, tag-expression parser and
expression engine (versions pinned to the framework's Cucumber BDD), so "what will run" matches
Cucumber. Runs are serialised because they share `target/`; outputs are copied per run, which
gives an execution history. A Python/Node gateway was the alternative; it would have needed a
second authenticated REST service in front of the same Maven call, with no benefit.

## ADR-013 AI as an advisory layer around deterministic results
Every AI tool computes a deterministic result first (rule-based failure category, keyword plan,
file-to-scenario tracing, coverage gaps, Gherkin validation), and the model adds an opinion or a
draft on top that is validated before use. This keeps AI optional (AC29), makes failures
explainable, and means a weak or unavailable model degrades quality but never correctness.
Generated code is proven, not trusted: it is compiled in memory against the real framework and its
step bindings are checked with Cucumber's engine. It reaches `src/` only through an admin's
explicit acceptance, which never overwrites. Claude uses the official Anthropic SDK; OpenAI,
Gemini and OpenAI-compatible local servers use their REST APIs through `java.net.http`, which
avoids three more SDKs and their transitive dependencies for one `complete()` call each.

## ADR-014 RAG as a separate retrieval service; answers stay in the MCP AI layer
The knowledge base is a Python FastAPI + ChromaDB service, as specified, with its own
authentication and access levels. The MCP server calls it for retrieval and context
(`/rag/query` with `answer=false`) and generates answers with its existing AI layer, so prompts,
budgets, redaction and citation checks live in one place. `/rag/query` can also answer on its own
for non-MCP clients. Hybrid search uses a small built-in BM25 next to ChromaDB vectors, fused by
reciprocal rank, instead of adding a search engine. The default embedder is the ONNX MiniLM model
bundled with ChromaDB (no PyTorch, which keeps it usable on 8 GB machines). Secrets and personal
data are redacted at ingestion, so the index itself can never leak them.

## ADR-015 Intelligent QA from run history, deterministic first
Selection, flakiness, correlation and maintenance are computed from the project's sources and a
per-scenario run history: MCP runs plus CI results imported with `import_execution_results`, so
CI shards and nightly runs count without a separate database. Outcomes come only from the
framework's own files. Classifications are simple, explainable rules (retry-pass or ≥ 2 flips =
flaky; last 2+ failed = consistently failing) instead of opaque scores. AI adds recommendations
that are validated against real tags or finding ids and never removes anything from a plan. Git
is read with fixed `git diff`/`git ls-files` argument lists and a validated ref; no shell.

## ADR-016 Production readiness on the existing pieces
Each service gets its own image (the MCP image carries a copy of the framework because runs are
`mvn test`), and one Compose file runs everything with health checks, memory and CPU limits,
rotated logs, named volumes and file-based secrets that hold only token hashes. ChromaDB runs as
its own container with a persistent volume, unreachable from outside the Compose network.
Monitoring is Prometheus-format `/metrics` on MCP and RAG (counts and timings only), plus the
existing JSON summaries and audit logs. Quality gates are Checkstyle, SpotBugs (effort max) and
Trivy, all in CI. Performance settings follow measurements on the target machine (docs/PERFORMANCE.md).
