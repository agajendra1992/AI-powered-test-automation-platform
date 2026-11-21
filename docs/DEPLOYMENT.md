# Deployment

The platform has five parts. Only the first is needed to run tests. The rest are optional and can
be deployed independently.

| Part | Runs as | Needs |
|---|---|---|
| Framework (Playwright Java + Cucumber + TestNG) | `mvn test` locally, in CI, or in the `tests` container | JDK 21+, Maven, browsers; the application under test |
| MCP server | `java -jar qa-mcp-server.jar` or the `mcp` container | The framework sources (it runs `mvn test`), a tokens file |
| AI layer | Inside the MCP server | A provider and key (`QA_AI_*`); off by default |
| RAG service | `python -m app.main` or the `rag` container | ChromaDB (embedded, or the `chroma` container), a keys file |
| Demo application | `sut/` or the `sut` container | Only for local and CI runs against the demo |

## Local: everything in Docker

```bash
scripts/dev-secrets.sh
```

```bash
docker compose --profile platform up -d --wait
```

* MCP: `http://127.0.0.1:8765/mcp`, with the bearer token from `secrets/mcp-runner.token` (or
  the viewer or admin token). The health check is at `/health`, metrics at `/metrics`.
* RAG: `http://127.0.0.1:8090`. Index the project once with the MCP tool
  `ingest_project_knowledge`; the project is mounted read-only at `/knowledge`.
* State lives in named volumes and survives `docker compose down`: `chroma-data` (vectors),
  `rag-data` (registry and audit), `mcp-state` (run history, drafts, audit, AI usage).
  `docker compose down -v` deletes all of it.
* Resource limits (`mem_limit`, `cpus`) are set per service. The whole stack needs about 4 GB.
  The MCP container's limit covers Maven, the test JVM and the browsers of one run.
* Logs: JSON files, rotated at 10 MB × 3 (`docker compose logs -f mcp`).

**The MCP image contains a copy of the framework.** Rebuild it after changing tests
(`docker compose --profile platform build mcp`). To work against live sources instead, bind-mount
the repository at `/work/project`. Runs then write to your `target/`, so don't run Maven on the
host at the same time.

## CI

GitHub Actions run the framework directly on the runner (see the README): PR smoke, nightly sharded
regression, weekly cross-browser, a Docker smoke test when container files change, and the MCP and
RAG test jobs. Secrets for `qa`/`staging` come from GitHub Environments.

## A shared (team) deployment

1. **Secrets.** Create tokens in your secret manager. Only their SHA-256 hashes go into
   `mcp-tokens.json` and `rag-keys.json`; see `scripts/dev-secrets.sh` for the format. Mount them
   as Docker secrets. Pass provider keys (`ANTHROPIC_API_KEY`, …) as environment secrets, never in
   files in the image.
2. **Network.** Put the MCP server (8765) and the RAG service (8090) behind a TLS-terminating
   reverse proxy. Expose `/metrics` only to the monitoring network; keep ChromaDB internal.
3. **Environments.** Point the framework at real environments with `-Denv=qa|staging` (MCP:
   the `environment` argument). Credentials come from environment variables. Keep `staging` in
   `QA_MCP_APPROVAL_ENVS` so runs there need a second person.
4. **Roles.** Use viewer tokens for dashboards and read-only assistants, runner tokens for
   people and bots that start runs, and admin tokens only for approvers.
5. **One run at a time per MCP instance** (runs share `target/`). For more throughput, run several
   MCP instances with separate state volumes, or let CI shard the regression.
6. **Backups.** Back up the `mcp-state` volume (history and audit). The RAG index can be rebuilt
   at any time with `ingest_project_knowledge` and `ingest_execution_results`.

## Configuration reference

* Framework: README → Configuration.
* MCP server: [MCP.md](MCP.md) → Configuration.
* AI: [AI.md](AI.md) → Providers.
* RAG: [RAG.md](RAG.md) → Configuration.

## Monitoring

| Signal | Where |
|---|---|
| Run results | `target/execution-summary.json` (totals, flaky, retries, failures by type, API timings, build, commit), failure records, Allure |
| MCP health and metrics | `GET /health`; `GET /metrics` (Prometheus): tool calls by tool/outcome, tool time, authentication failures, executions by status, queue length |
| RAG health and metrics | `GET /rag/health`; `GET /rag/metrics`: requests by path/status, request time, chunk count |
| Audit | `<mcp-state>/audit.log`, `<rag-data>/audit.log` (JSON lines: who called what, outcome) |
| AI spend | `get_ai_usage` / `<mcp-state>/ai-usage.jsonl` (tokens per provider, model, purpose, day) |
| Test health | `get_flaky_tests`, `correlate_failures`, `suggest_test_maintenance` |

Suggested alerts: `qa_mcp_auth_failures_total` rising (token misuse); `qa_mcp_executions{status="ERROR"}`
or `TIMED_OUT` increasing (environment problems); `qa_mcp_queue_length` > 3 for 30 minutes; the
RAG health check failing; the daily AI token budget above 80 %.
