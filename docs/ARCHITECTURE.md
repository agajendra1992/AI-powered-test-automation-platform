# Architecture

`CURRENT_ARCHITECTURE.md` is the Phase 0 assessment. This document describes the platform as built.
The design decisions behind it, with their reasons, are in
[ARCHITECTURE_DECISIONS.md](ARCHITECTURE_DECISIONS.md) (ADR-001 to ADR-015).

```
                    ┌──────────────── AI assistants / IDEs / CI bots ────────────────┐
                    │              MCP (stdio or HTTP + bearer token)               │
                    ▼                                                               │
┌──────────────────────────── MCP server (mcp-server/, Java) ───────────────────────────────┐
│ GuardedTools: identity → role check → argument validation → handler → redaction → audit   │
│                                                                                           │
│ Discovery        Execution              Reporting        Browser        AI layer          │
│ (Gherkin parser, (ExecutionService:     (ReportReader:   inspection     (optional)        │
│  expressions,     queue, approvals,      summary,        (allow-listed  failure analysis, │
│  tag parser)      timeout, history)      failures,       origins)       plans, generation,│
│                        │                 Cucumber,                      selection,        │
│                        │                 Allure, logs)                  coverage, insights│
│                        │ ProcessBuilder("mvn", fixed args)              │        │        │
└────────────────────────┼────────────────────────────────────────────────┼────────┼────────┘
                         ▼                                                ▼        ▼
┌──────────── Framework (unchanged by MCP) ───────────┐            LLM provider  RAG service
│ Maven → TestNG suite → BaseCucumberRunner           │            (Anthropic SDK, (rag/, FastAPI)
│   parallel DataProvider → Cucumber scenarios        │             OpenAI, Gemini,  hybrid search,
│   PicoContainer ScenarioContext (per scenario)      │             local)           access levels,
│   steps → page objects / components / API services │                              citations
│   Playwright: BrowserPool (per worker) +            │                                   │
│               BrowserSession (context per scenario) │                               ChromaDB
│   Listeners → execution-summary.json, failure       │
│   records, Allure (Cucumber adapter), traces        │
└─────────────────────────┬───────────────────────────┘
                          ▼
              Application under test (demo: sut/)
```

### Optional orchestration layer (LangChain + LangGraph)

```
 client ──/ai/execute──▶ ai-orchestration/ (FastAPI)
                          LangGraph StateGraph, 14 nodes, SQLite checkpoints, interrupt() for approval
                          LangChain: chat model + prompts + Pydantic output │ MCP tools │ RAG retriever
                                 │ HTTP + runner token                       │ HTTP + key
                                 ▼                                           ▼
                          MCP server (above, unchanged)                RAG service (unchanged)
```

The workflow service is a client of the MCP server and the RAG service: it adds no execution
engine, no second index, and no route around MCP's own roles and approvals. Deterministic code
validates, authorises, asks for approval and decides what runs; the model (optional) interprets
the request, analyses failures and suggests next steps. Details: [AI_ORCHESTRATION.md](AI_ORCHESTRATION.md).

## Layers and rules

| Layer | Responsibility | Never |
|---|---|---|
| Feature files | Business behaviour by role and intent, standard tags | Credentials, selectors, browser code |
| Step definitions | Thin glue: call a page object or service, store in ScenarioContext, assert | Business logic, locators, sleeps |
| Page objects / components | Locators (role → label → test id) and user actions | Assertions, test data |
| API services | Typed calls over `APIRequestContext`, auth providers, schema checks | Test-specific state |
| Core | Config, data, reporting, listeners, retry, sharding | Depend on UI or AI |
| MCP / AI / RAG | Discovery, orchestration, analysis, advice | Change a test result, run anything but `mvn`, write `src/` without admin acceptance |
| AI orchestration (LangGraph) | Request → plan → approval → MCP run → results, analysis | Execute without permission and approval, retry a run-starting call, decide pass/fail |

## Data flow of one run

1. A request comes from the CLI, CI or an MCP tool, which validates it and may require approval.
2. `mvn test -DsuiteXmlFile=…` runs TestNG, which runs the Cucumber scenarios in parallel.
3. Each scenario gets its own context and browser context, and cleans up its data.
4. Listeners write the outputs (summary, failure records, Cucumber JSON, Allure, logs, traces).
5. MCP copies them into the run's history; RAG can index them; insights read the history.
6. AI reads the evidence and history and gives labelled opinions. The status stays the framework's.

## Contracts between parts

* **`target/execution-summary.json` and `target/test-failures/*.json`** are the stable
  machine-readable interface (ADR-006). Everything downstream, including a future central
  reporting platform, reads these, not Allure.
* **MCP tools** have closed JSON schemas and a role each (see MCP.md); results are structured JSON.
* **RAG HTTP API** (see RAG.md): search and query return citations; ingest is incremental.
* **AI orchestration API** (see AI_ORCHESTRATION.md): `/ai/execute`, `/ai/executions/{id}` (+ `/state`,
  `/approve`, `/reject`), `/ai/failure-analysis`; the final response carries `test_result` with
  `test_result_source` = the framework.

## Security model (summary)

Read-only by default; roles viewer < runner < admin; a second person approves staging runs;
tokens and keys stored as hashes; every call audited; inputs validated against allow-lists;
redaction of secrets and personal data in outputs, prompts and the index; untrusted text fenced
for models; no shell, and Maven (plus fixed `git diff`) is the only program started. Details:
MCP.md → Security controls, RAG.md → Security, AI.md → Ground rules.
