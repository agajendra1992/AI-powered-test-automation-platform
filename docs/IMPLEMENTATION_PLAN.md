# Implementation Plan

Each phase ends with: compile, run, record results in `IMPLEMENTATION_STATUS.md`. A phase
starts only when the previous one is verified.

| Phase | Scope | Exit criteria |
|---|---|---|
| 0 | Assessment, gap analysis, plan | These three documents |
| 1 | Core engine, Cucumber + TestNG, UI / API / hybrid, hooks, ScenarioContext, suites, Allure, demo app | AC01–AC14, AC29 verified locally |
| 2 | GitHub Actions (smoke, regression, api, ui, e2e, cross-browser, sharding, Allure publishing), test Docker image | AC15–AC17 |
| 3 | MCP gateway: discovery, async execution, results, reports, browser inspection, security | AC18–AC20 |
| 4 | LLM abstraction (Anthropic, OpenAI, Gemini, local), prompt manager, token tracking, NL execution, generation, failure analysis | AC21–AC23 |
| 5 | RAG service: ingestion, chunking, embeddings, ChromaDB, hybrid search, rerank, citations | AC24–AC26 |
| 6 | Impact selection, coverage gaps, duplicate scenarios, flaky analytics | AC27 |
| 7 | Docker compose for all services, security validation, runbooks, onboarding | AC28, AC30–AC32 |

## Repository layout decision

The prompt suggests separate top-level modules (`automation-core`, `ui-automation`, …).
Phase 1 uses **one Maven module with packages per layer** instead:

* All Java layers ship and version together and have no independent consumers yet.
* One module keeps the build fast and avoids empty modules (explicitly discouraged).
* Package boundaries (`core`, `ui`, `api`, test-side `bdd`) mirror the suggested modules,
  so splitting into Maven modules later is a mechanical move.

Non-Java components (`mcp-server/`, `rag-service/`, `sut/`) are separate directories with
their own dependency files because they are separately deployable.

## Migrating an existing suite (if one turns up)

1. Copy feature files into `src/test/resources/features/<domain>/`.
2. Re-point step definitions to the page objects and services here, deleting duplicates.
3. Run `mvn test -Dcucumber.filter.tags=@<domain>` and fix one domain at a time.

---

# Architecture update: LangChain + LangGraph (2026-11-20)

The original master prompt remains the source of truth. This update adds LangChain (LLM
integration, prompts, structured output, tools, retrieval) and LangGraph (stateful workflow
orchestration) **without replacing anything that works**.

## 1. Assessment of the current implementation

| Phase | Status | Open items |
|---|---|---|
| 0 Assessment | ✅ | – |
| 1 Core Java BDD | ✅ 37/37 on 3 browsers | – |
| 2 CI + Docker | ✅ (local) | Workflows not yet run on GitHub (AC15–17 🟡) |
| 3 MCP server (Java) | ✅ 57 tools, stdio + HTTP, roles, approvals, audit | – |
| 4 LLM layer (Java, inside the MCP server) | ✅ built and tested | No live cloud-model output yet (AC21–23, AC27 🟡) |
| 5 RAG (Python FastAPI + ChromaDB, `rag/`) | ✅ | No LLM-written answer verified |
| 6 Intelligent QA | ✅ | – |
| 7 Production readiness | ✅ Docker platform, SpotBugs, Trivy, docs | GitHub CI runs, Linux hosts |

**Current AI-related components**
* **MCP server (Java, `mcp-server/`):** discovery, execution through `mvn test`, reports,
  approvals (staging needs a second admin), audit, metrics, browser inspection.
* **Java AI layer (inside the MCP server):** `LlmProvider` (Anthropic SDK, OpenAI, Gemini, local),
  `PromptManager`, token tracking, and tools such as `analyze_scenario_failure` (deterministic
  evidence and rules plus an optional model opinion) and `plan_test_execution`.
* **RAG service (Python, `rag/`):** ingestion, chunking, ChromaDB, hybrid search, citations,
  access levels, `/rag/search` and `/rag/query`.

## 2. Mapping the new architecture onto the existing code

| New requirement | Existing equivalent | Decision |
|---|---|---|
| Java Playwright + Cucumber + TestNG execution | Framework | **Unchanged.** Still the only execution engine |
| MCP exposes the framework | Java MCP server (57 tools) | **Unchanged.** LangGraph calls its existing tools over HTTP with a runner token |
| LangChain: LLM providers | Java `LlmProvider` (for MCP-native clients) | **New, in Python:** LangChain chat models (`langchain-anthropic` default `claude-opus-5-5`, `langchain-openai` incl. OpenAI-compatible local servers, `langchain-google-genai`), chosen through a factory; `none` = deterministic only |
| LangChain: prompts, structured output, validation | Java `PromptManager`, JSON validation | **New, in Python:** `ChatPromptTemplate`s, Pydantic schemas with `with_structured_output`, validation against MCP facts (real tags, environments, scenarios) |
| LangChain: MCP tool integration | Python MCP client used in tests only | **New:** `langchain-mcp-adapters` turns the Java server's tools into LangChain tools; one adapter class wraps the calls the graph needs (timeouts, controlled retries, typed results) |
| LangChain: RAG retriever | `rag/` HTTP API | **New retriever only:** a LangChain `BaseRetriever` over `POST /rag/search`. **No second ingestion pipeline, no second ChromaDB client** |
| LangChain: token usage and cost | Java `TokenUsageTracker` | **New for the Python service:** usage from LangChain's `usage_metadata`, a JSONL log, a daily budget, and a cost estimate only from prices you configure (none hard-coded) |
| LangGraph orchestration | `plan_test_execution` → `execute_test_plan` (Java, one step at a time) | **New:** `StateGraph` with typed state, the 14 nodes, conditional edges, SQLite checkpointing and `interrupt()` for human approval. Deterministic Python for validation, authorisation, approval and execution decisions; the LLM only interprets the request, analyses failures and writes recommendations |
| FastAPI endpoints `/ai/*` | – | **New service `ai-orchestration/`** following the RAG service's conventions (bearer keys stored as hashes, roles, JSON audit log, `/health`, `/metrics`) |
| Failure analysis | MCP `analyze_scenario_failure` (evidence + rules) | Graph calls it with `use_ai=false` (deterministic evidence, no second model call in Java), adds RAG evidence, then one LangChain structured analysis, with cited evidence verified |
| Allure / results | MCP reporting tools | Reused (`get_execution_summary`, `get_failed_scenarios`, `get_allure_report`, `get_scenario_logs`) |

### Where the new code lives: a separate `ai-orchestration/` service (decision)

The update asks to integrate LangChain into "the existing Python AI service" and also proposes an
`ai-orchestration/` structure with its own `main.py`. The existing Python service is the RAG
service. **Decision: a separate, small `ai-orchestration/` FastAPI service, following the RAG
service's API conventions.** Reasons:
* The RAG service is a data service that the Java MCP server also uses. It should not depend on
  LangChain or LangGraph, workflow state or MCP client sessions, and an orchestration failure
  must not take retrieval down.
* Orchestration has long-running, checkpointed workflows and heavy dependencies; RAG needs
  neither.
* Responsibilities stay separate, as required: **MCP = tools and execution, RAG = knowledge,
  LangGraph = workflow.** It adds one container, not several, and is optional like the rest of the AI.

*Alternative considered:* mounting the `/ai` routes into the RAG app. That's one process fewer,
but it couples retrieval to the orchestration dependencies and lifecycle. It can be revisited if a
single Python container is preferred.

### Relationship to the existing Java AI layer

The Java AI tools stay for MCP-native clients (e.g. Claude Code calling `analyze_scenario_failure`
directly). The LangGraph service is the orchestration path. To avoid duplicate model calls, the
graph calls the MCP tools with `use_ai=false`; LLM reasoning in the workflow happens once, in LangChain.

## 3. Files

**New (`ai-orchestration/`)**
```
ai-orchestration/
  requirements.txt, requirements-dev.txt, Dockerfile, .dockerignore
  main.py                        # uvicorn entry point
  app/
    api.py                       # FastAPI routes (/ai/*), auth dependency, background workflows
    config.py                    # AI_* settings, secrets from files
    graph/      state.py, workflow.py, edges/routing.py,
                nodes/ intake.py, discovery.py, planning.py, execution.py, analysis.py, deps.py
    langchain/  providers/factory.py, prompts/templates.py, output_parsers/schemas.py,
                retrievers/rag_retriever.py, structured.py, usage.py
    mcp/        client.py (langchain-mcp-adapters session, timeouts, retries), adapters.py (typed calls)
    persistence/ store.py (workflow index; checkpoints use LangGraph's AsyncSqliteSaver)
    security/   auth.py (keys, roles), redaction.py, policy.py (deterministic permission/approval rules)
    observability/ telemetry.py (audit log, Prometheus counters, node timings)
  tests/        test_langchain_layer.py, test_workflow.py, test_api.py (fakes),
                test_integration_tools.py (real MCP + RAG, opt-in)
```
**Modified:** `docker-compose.yml` (new `ai` service in the `platform` profile),
`scripts/dev-secrets.sh` (keys for the new service and its MCP/RAG tokens), `.github/workflows/ci.yml`
(test job), `.github/dependabot.yml`, `README.md`, `docs/ARCHITECTURE.md`, `IMPLEMENTATION_STATUS.md`,
plus `docs/AI_ORCHESTRATION.md` (new guide). **No Java or RAG code changes are planned.**

## 4. Implementation phases for the update

| Phase | Scope | Done when | Status |
|---|---|---|---|
| 4b LangChain layer | Provider factory, prompt templates, Pydantic output schemas, response validation, usage/cost tracking, error handling | Unit tests with a fake chat model; provider wire checks against stubs | ✅ 8 tests |
| 5b Tools and retrieval | MCP client + `langchain-mcp-adapters`, typed adapter with timeouts/retries; RAG retriever over `/rag/search` with fallback | Integration test against the real Java MCP server and RAG service | ✅ 4 integration tests on the live stack |
| 6b LangGraph workflow | Typed state, 14 nodes, conditional routing (invalid → error, no match → alternatives, approval pause/reject/grant, monitor loop, failure analysis, RAG fallback), SQLite checkpointing, interrupt/resume | Unit tests per route with fakes; resume after restart | ✅ 10 tests |
| 6c API | `/ai/execute`, `/ai/executions/{id}` (+ `/state`, `/approve`, `/reject`), `/ai/failure-analysis`; auth, roles, audit, timeouts | API tests | ✅ 5 tests |
| 7b Production | Dockerfile, Compose service, secrets, health/metrics, CI job, docs | Full real run: request → plan → approval → Java execution through MCP → results → failure analysis | ✅ real runs passed (see IMPLEMENTATION_STATUS.md); image built |

**Rules carried over:** the test result always comes from the framework; nothing executes without
permission and approval; no credentials in prompts, logs or responses; deterministic code decides
validation, authorisation, approvals and execution; the Maven framework stays independent of all AI.
