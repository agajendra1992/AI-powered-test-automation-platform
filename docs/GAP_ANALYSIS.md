# Gap Analysis

Baseline: empty repository (see `CURRENT_ARCHITECTURE.md`). Every capability is a gap; this
table records how each is closed and in which phase, so nothing is silently dropped.

| Capability | Gap | Closed by | Phase |
|---|---|---|---|
| UI automation (Playwright Java, POM + components) | Missing | `io.qaplatform.ui` | 1 |
| API automation (APIRequestContext) | Missing | `io.qaplatform.api` | 1 |
| Hybrid UI + API flows | Missing | `features/e2e`, `AuthState`, `ScenarioContext` | 1 |
| Cucumber on TestNG, scenario-parallel | Missing | `bdd/runners` (`AbstractTestNGCucumberTests`) | 1 |
| TestNG XML suites (smoke/regression/ui/api/e2e) | Missing | `testng-*.xml` | 1 |
| Thread-safe browser lifecycle | Missing | `BrowserPool` + `BrowserSession` | 1 |
| Diagnostics (screenshot, trace, console, network, video) | Missing | `BrowserSession`, hooks | 1 |
| Retry without hiding failures | Missing | `RetryAnalyzer`, failure records | 1 |
| Secret redaction in reports/logs | Missing | `Redactor` | 1 |
| Execution metrics JSON | Missing | `ExecutionListener` → `target/execution-summary.json` | 1 |
| Static quality gate | Missing | Checkstyle (bans JUnit/Selenium/Rest Assured imports, sleeps) | 1 (SpotBugs: 7) |
| System under test for verification | None available | `sut/` FastAPI demo app | 1 |
| GitHub Actions workflows | Missing | `.github/workflows` | 2 |
| Docker images + compose | Missing | `docker/` | 2 / 7 |
| MCP server | Missing | `mcp-server/` | 3 |
| LLM abstraction, prompts, token tracking | Missing (draft code parked) | `ai-engine` | 4 |
| RAG service (FastAPI + ChromaDB) | Missing | `rag-service/` | 5 |
| Impact-based selection, flaky analytics | Missing | `test-orchestration` | 6 |
| Formal evaluation of AI output quality | Missing (found 2026-11-24 review) | `ai-orchestration/evals`, `ai-eval.yml` | update |
| Notifications, test-management sync | Missing (found 2026-11-24 review) | `scripts/notify.py`, `scripts/testmgmt_export.py`, report job | update |
| Contract testing | Missing (found 2026-11-24 review) | Pact consumer + provider verification, `testng-contract.xml` | update |
| Performance / load testing | Missing (found 2026-11-24 review) | k6 `perf/k6/api-load.js`, `performance.yml`, CI smoke | update |
| Accessibility scans (WCAG 2.1 AA) | Missing (found 2026-11-24 review) | `io.qaplatform.ui.a11y` (axe-core), `features/accessibility` | update |
| Visual regression | Missing (found 2026-11-24 review) | `io.qaplatform.ui.visual`, `features/visual`, Linux baselines | update |

## Risks

| Risk | Mitigation |
|---|---|
| Cucumber + TestNG parallelism configured twice (suite `parallel` and DataProvider) | Only the DataProvider is parallel; suites set `data-provider-thread-count` |
| Allure duplicates when both TestNG and Cucumber adapters are present | Only `allure-cucumber8-jvm` is on the classpath (was `allure-cucumber7-jvm` before the 2026-11-21 upgrade) |
| Third-party demo sites are unstable | Bundled demo app (`sut/`) with deterministic data |
| Leaking tokens via Allure attachments | `Redactor` applied to every API attachment and log line |
