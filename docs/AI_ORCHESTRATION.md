# AI orchestration (LangChain + LangGraph)

`ai-orchestration/` is an optional Python service that turns a natural-language request such as
*"Execute all payment regression scenarios in QA using Chromium"* into a checked, approved run of
the **existing** Java framework through the **existing** MCP server, then collects the results,
analyses failures and writes recommendations.

It adds no second execution engine. Maven, TestNG, Cucumber and the MCP server work exactly as
before without it, and **the test result always comes from the framework**.

| Part | Job | Never |
|---|---|---|
| MCP server (Java) | Tools: discovery, execution (`mvn test`), reports, approvals for staging | — (unchanged) |
| RAG service | Knowledge with citations (`/rag/search`) | — (unchanged) |
| LangChain (in this service) | Chat models, prompt templates, Pydantic structured output, MCP tools via `langchain-mcp-adapters`, RAG retriever, usage and cost tracking | Decide pass/fail, permissions or approvals |
| LangGraph (in this service) | The workflow: typed state, 14 nodes, conditional routing, checkpoints, human approval | Run anything that has not passed permission and approval |

## Workflow

```
user_request → intent_classification → request_validation ─invalid/unclear/unsupported─▶ final_response
                                              │
                                              ▼
                                       test_discovery ─no match (with alternatives)─▶ final_response
                                              │
                                              ▼
                       rag_retrieval (unavailable → labelled fallback, continues)
                                              │
                                              ▼
              execution_planning → permission_validation ─forbidden─▶ final_response
                                              │
                                              ▼
                     human_approval  ◀── interrupt(): pauses, checkpointed
                       │ rejected ─▶ final_response
                       ▼ approved (re-checked by policy)
                 mcp_execution ─error─▶ final_response
                       │
                       ▼
               execution_monitoring ⟲ (until final, deadline or 3 MCP errors)
                       │
                       ▼
               result_collection ─passed─▶ recommendation_generation → final_response
                       │ failed
                       ▼
               failure_analysis → recommendation_generation → final_response
```

**Deterministic code** does request validation (environment, browser, tag names that really
exist; the environment and browser always come from the request, defaults `local` and `chromium`, never from the model), authorisation, approval rules, tool choice and monitoring. **The model is used in at most
three small, separate calls**: interpreting the request (then validated against real tags), a
structured failure analysis (its cited evidence is checked against the MCP evidence; unverified
→ confidence capped and labelled *UNCONFIRMED HYPOTHESIS*), and recommendations (dropped unless
they reference real facts). With `AI_LLM_PROVIDER=none` every step has a rule-based path.

Every selection adds `not @quarantine`. If a request covers only part of the regression, the plan
says how many regression test cases are left out (`not_included`), so nothing is skipped silently.

## API

All `/ai/*` calls need `Authorization: Bearer <key>`. Keys are stored as SHA-256 hashes
(`AI_KEYS_FILE`). Roles: `viewer` < `operator` < `approver`.

| Method and path | Role | Purpose |
|---|---|---|
| `POST /ai/execute` `{"request": "..."}` | operator | Start a workflow; returns `202` and a `workflow_id` at once |
| `GET /ai/executions/{workflow_id}` | viewer | Status (`RUNNING`, `AWAITING_APPROVAL`, `COMPLETED`, `REJECTED`, `INVALID`, `NO_MATCH`, `NEEDS_CLARIFICATION`, `UNSUPPORTED`, `FORBIDDEN`, `ERROR`, `MONITOR_TIMEOUT`, `PAUSED`), the pending plan, the MCP run id and the final response |
| `GET /ai/executions/{workflow_id}/state` | viewer | The full checkpointed graph state (redacted) |
| `POST /ai/executions/{workflow_id}/approve` `{"note": ""}` | approver who is not the requester | Resume the paused workflow; the graph checks the policy again |
| `POST /ai/executions/{workflow_id}/reject` | approver or the requester | Stop it; nothing runs |
| `POST /ai/failure-analysis` `{"execution_id": "run-…", "scenario_name": null}` | operator | Analyse the failed scenarios of any finished MCP run |
| `GET /health`, `GET /metrics` | none | Liveness; Prometheus counters (workflows, node runs and timings, outcomes, auth failures) |

**Approval rules:** production is never executed. Every execution, in every environment, needs
approval from a second person: someone with the `approver` role who is **not** the requester (an
approver's own requests need another approver). The requester may still reject their own plan.
For environments in `AI_APPROVAL_ENVS` (default `staging`), MCP's own approval (a second MCP admin)
also applies on top.

## Running it

```bash
cd ai-orchestration && python3.12 -m venv .venv && .venv/bin/pip install -r requirements.txt -r requirements-dev.txt
```

```bash
AI_KEYS_FILE=secrets/ai-keys.json AI_MCP_URL=http://127.0.0.1:8765/mcp AI_MCP_TOKEN_FILE=secrets/ai-mcp.token AI_RAG_URL=http://127.0.0.1:8090 AI_RAG_KEY_FILE=secrets/rag-mcp.token .venv/bin/python main.py
```

In Docker it is the `ai` service of the `platform` profile (`scripts/dev-secrets.sh` creates its
keys, `docker compose --profile platform up -d --wait`, port `127.0.0.1:8095`).

```bash
curl -s -X POST http://127.0.0.1:8095/ai/execute -H "Authorization: Bearer $(cat secrets/ai-operator.token)" -H 'content-type: application/json' -d '{"request":"Execute all payment regression scenarios locally using Chromium"}'
```

## Setting a provider key

Keys never go in the repository. Store one in a private file (hidden prompt, mode 600) and check it:

```bash
scripts/set-llm-key.sh openai
```

```bash
scripts/set-llm-key.sh openai --check
```

The check asks OpenAI for its model list (free) and prints the model ids the key can use. It does not prove the account has credits; the first real call does (429 `insufficient_quota` means no credits).

Verified live with `gpt-4o-mini` (low cost, no hidden reasoning tokens): a full workflow with failure analysis used about 2,000–2,500 tokens (≈ $0.0005). To cap spend, set `AI_DAILY_TOKEN_BUDGET` (e.g. `100000` ≈ at most $0.06/day for that model) and the two price variables to see the estimate in `llm_calls`. Pick one
(no model id is guessed for OpenAI), then load the key into the shell that starts the services:

```bash
set -a; . ~/.config/qa-platform/openai.env; set +a; AI_LLM_PROVIDER=openai AI_LLM_MODEL=<model-id> QA_AI_PROVIDER=openai QA_AI_MODEL=<model-id> SUT_PORT=8001 docker compose --profile platform up -d --wait
```

`anthropic` and `gemini` work the same way. Running the script again replaces the stored key; to
remove it, delete the file. `/health` shows the active model, or why the model is disabled.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `AI_KEYS_FILE` | – | API keys (hashes + roles). Without it every protected call is rejected |
| `AI_MCP_URL`, `AI_MCP_TOKEN` / `AI_MCP_TOKEN_FILE` | – | MCP server (HTTP) and a **runner** token of its own |
| `AI_RAG_URL`, `AI_RAG_KEY` / `AI_RAG_KEY_FILE` | – | RAG service; optional (a labelled fallback is used without it) |
| `AI_LLM_PROVIDER` | `none` | `none`, `anthropic`, `openai`, `gemini`, `local` (OpenAI-compatible, e.g. Ollama) |
| `AI_LLM_MODEL` | `claude-opus-5-5` for anthropic | Model id; required for the other providers |
| `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `GOOGLE_API_KEY` | – | Provider key (environment or secret store only) |
| `AI_DAILY_TOKEN_BUDGET` | 500000 | Model calls stop (rules take over) when the day's tokens reach it |
| `AI_PRICE_INPUT_PER_MTOK`, `AI_PRICE_OUTPUT_PER_MTOK` | unset | Cost estimate only if you set prices; none are hard-coded |
| `AI_ALLOWED_ENVS`, `AI_APPROVAL_ENVS` | `local,qa,staging` / `staging` | Environments that may run / where MCP also holds runs for its own admin approval |
| `AI_MCP_TIMEOUT_SECONDS`, `AI_MONITOR_TIMEOUT_MINUTES`, `AI_MONITOR_POLL_SECONDS` | 90 / 45 / 60 (max 60) | Per-call timeout, run deadline, long-poll interval |
| `AI_DATA_DIR` | `.ai-data` | Checkpoints (SQLite), workflow index, audit log, LLM usage |

**Timeouts and retries:** every MCP call has a timeout. Read-only calls are retried twice with
back-off; calls that start or change something (`run_*`, `approve_*`, …) are **never** retried.
Three MCP errors in a row while monitoring end the workflow as `ERROR`; the run itself keeps going
in MCP and can be inspected there.

**Checkpointing:** each step is saved in `checkpoints.db`. A workflow waiting for approval survives
a restart and resumes from the approve/reject call; a workflow interrupted mid-run shows `PAUSED`.

## Security

* Keys and tokens only from files or the environment, stored as hashes; never logged or returned.
* Requests, retrieved documents, feature text and logs are untrusted: redacted, then fenced in
  `<data>` blocks before reaching a model; RAG snippets with instruction-like text are flagged.
* The model's output is schema-validated and checked against MCP facts; it cannot pick tags that do
  not exist, choose an environment outside the allow-list, approve anything, or change a result.
* Responses and state are redacted before they leave the service; every call is audited
  (`audit.log`, JSON lines).

## Tests

```bash
cd ai-orchestration && .venv/bin/python -m pytest -q tests
```

Unit tests use fakes for MCP, RAG and the model (every route, approval, rejection, restart
resume, structured output via a stubbed Anthropic endpoint). Set `AI_IT_MCP_URL`,
`AI_IT_MCP_TOKEN_FILE`, `AI_IT_RAG_URL` and `AI_IT_RAG_KEY_FILE` to also run the integration tests
against a live MCP server and RAG service.
