# Current Architecture (Phase 0 assessment)

Date: 2026-11-18

## Starting point

The requested base repository (`pw_updated_agent_framework`) was not found on the
workstation, and GitHub CLI access was not authenticated, so it could not be cloned.
The project owner chose to **start a new repository**. This is therefore a greenfield
build, and the "preserve existing code" rule applies to nothing yet.

Other frameworks found on the machine (not used as the base, listed for traceability):

| Location | Stack observed | Notes |
|---|---|---|
| `~/Downloads/pw-framework-agents` | Playwright + Cucumber + TestNG, `e2e.yml` | Closest match; candidate source for migrating existing scenarios later |
| `~/Documents/pw-java-framework` | Playwright + Cucumber + TestNG, `e2e.yml` | No git remote |
| `~/Downloads/AUTOMATION` | Playwright + Selenium + Rest Assured, multi-module | Conflicts with "no Selenium / no Rest Assured" rule |

If one of these should be merged in later, see "Migrating an existing suite" in
`IMPLEMENTATION_PLAN.md`.

## Target architecture (summary)

```
                ┌──────────────────────────── CI/CD (GitHub Actions) ─────────────────────────┐
                │  smoke · regression · api · ui · e2e · cross-browser · AI analysis (Phase 2+) │
                └───────────────────────────────┬──────────────────────────────────────────────┘
                                                │  mvn test -DsuiteXmlFile=… -Dcucumber.filter.tags=…
┌───────────────────────────────────────────────▼──────────────────────────────────────────────┐
│ Layer 2  BDD + execution:  TestNG XML suite → AbstractTestNGCucumberTests runner (parallel DP) │
│          features/ · steps/ · hooks/ · ScenarioContext (PicoContainer, scenario-scoped)        │
├───────────────────────────────────────────────────────────────────────────────────────────────┤
│ Layer 1  Core engine (src/main/java/io/qaplatform)                                            │
│   core: config · data · reporting · listeners · exceptions · assertions                      │
│   ui:   browser (BrowserPool per worker, BrowserSession per scenario) · pages · components     │
│   api:  client (APIRequestContext) · auth · services · models · requests/responses · schemas  │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
        Layers 3-6 (MCP, LLM, RAG, intelligent selection) consume files written by Layer 1/2:
        target/test-failures/*.json · target/execution-summary.json · allure-results · cucumber.json
```

Key decisions are recorded in `docs/ARCHITECTURE_DECISIONS.md`.
