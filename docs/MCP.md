# MCP server

`mcp-server/` lets AI assistants (Claude Code, Claude Desktop, IDE agents, any MCP client)
discover Cucumber scenarios, run them through the real Java framework, and read the results and
failure evidence.

## How it works

```
MCP client ──stdio or HTTP──> qa-mcp-server (Java, MCP SDK 2.2.0)
                                │  discovery: Cucumber's Gherkin parser + expression engine over the sources
                                │  execution: ProcessBuilder("mvn", "-B", "clean", "test", "-DsuiteXmlFile=…", …)  ← no shell
                                ▼
                     Framework: Maven → TestNG → Cucumber → Playwright   (unchanged; knows nothing about MCP)
                                │
                                ▼  target/execution-summary.json, test-failures/, cucumber-reports/, allure-results/, logs/
                     copied to .qa-mcp/executions/<id>/results  → reporting tools
```

* **No second engine.** Every run is the framework's own `mvn test` with one of its TestNG suites.
  The server only chooses validated parameters (suite, tags, scenario name, environment, browser,
  threads, retries). The framework does not depend on the MCP module (AC29).
* **Results come only from the framework.** A run's status comes from Maven's exit code and the
  framework's `execution-summary.json`. A run without a summary is `ERROR`, and a non-zero exit
  is never `PASSED`. No tool can change a result.
* **Asynchronous.** `run_*` tools return an `executionId` at once. Runs execute one at a time,
  because they share `target/`. Use `get_execution_status` with `wait_seconds` (up to 60) to wait.
  History survives restarts; a run that was cut off by a restart becomes `ERROR`.

## Build and connect

```bash
mvn -f mcp-server/pom.xml package
```

This produces `mcp-server/target/qa-mcp-server.jar`. Java 21+ and Maven are required on the
machine that runs it, because the server starts the framework with `mvn`.

### Claude Code (stdio)

Copy [`mcp-server/examples/claude-code.mcp.json`](../mcp-server/examples/claude-code.mcp.json)
to `.mcp.json` in the project root (adjust the jar path), or add the same entry to your user
configuration:

```json
{
  "mcpServers": {
    "qa-platform": {
      "command": "java",
      "args": ["-jar", "mcp-server/target/qa-mcp-server.jar"],
      "env": { "QA_MCP_PROJECT_DIR": ".", "QA_MCP_STDIO_ROLE": "runner" }
    }
  }
}
```

Without `QA_MCP_STDIO_ROLE` the stdio caller is a **viewer** (read-only).

### Shared server (streamable HTTP)

```bash
java -jar mcp-server/target/qa-mcp-server.jar new-token ci-bot runner
```

This prints a token once, plus a token-file entry that holds only the token's SHA-256 hash. Put
the entries in a JSON array file, then start the server:

```bash
QA_MCP_TRANSPORT=http QA_MCP_TOKENS_FILE=/secure/mcp-tokens.json QA_MCP_PROJECT_DIR=/srv/qa java -jar qa-mcp-server.jar
```

* Endpoint `http://127.0.0.1:8765/mcp`. Clients send `Authorization: Bearer <token>`; anything
  else gets 401. `GET /health` is open for health checks.
* It binds to loopback by default. If you expose it, put it behind TLS (a reverse proxy) and a
  firewall.
* Client entry, for example in Claude Code:
  `{"type": "http", "url": "https://qa-mcp.internal/mcp", "headers": {"Authorization": "Bearer ${QA_MCP_TOKEN}"}}`.

## Configuration

Set each value with `-Dqa.mcp.<key>` or the environment variable `QA_MCP_<KEY>`.

| Key | Default | Meaning |
|---|---|---|
| `project.dir` | `.` | Framework root (must contain `pom.xml` and `src/test/resources/features`) |
| `state.dir` | `<project>/.qa-mcp` | Execution history, copied results, `audit.log` |
| `transport` | `stdio` | `stdio` or `http` |
| `stdio.role` | `viewer` | Role of the local stdio caller: `viewer`, `runner`, `admin` |
| `tokens.file` | (none) | HTTP only: JSON array of `{name, role, sha256}` |
| `http.host` / `http.port` | `127.0.0.1` / `8765` | HTTP bind address |
| `allowed.envs` | `local,qa,staging` | Environments runs may target (only these three exist; production is not allowed) |
| `approval.envs` | `staging` | Environments whose runs wait for approval |
| `run.timeout.minutes` | `30` (max 120) | Hard limit per run; the whole process tree is stopped |
| `max.queued.runs` | `5` | Queue limit |
| `maven.command` | `mvn` | Only `mvn` or `mvnw` (any other program is rejected) |
| `browser.origins` | base URLs of the allowed envs | Extra origins the browser tools may open |

## Tools

Roles are cumulative: a runner can also use every viewer tool, and an admin every tool.

| Group | Tools | Role |
|---|---|---|
| Execution | `run_smoke_tests`, `run_regression_tests` (always the complete suite), `run_ui_tests`, `run_api_tests`, `run_e2e_tests`, `run_tests_by_tag`, `run_scenario_by_name`, `run_testng_suite`, `cancel_execution` | runner |
| Execution status | `get_execution_status`, `list_pending_approvals` | viewer |
| Approvals | `approve_execution`, `reject_execution` | admin |
| Cucumber discovery | `list_feature_files`, `list_scenarios`, `search_feature_files`, `get_scenario_details`, `list_cucumber_tags`, `get_step_definitions`, `search_step_definitions`, `find_duplicate_scenarios` | viewer |
| Reporting | `get_execution_summary`, `get_failed_scenarios`, `get_cucumber_report`, `get_allure_report`, `get_scenario_logs`, `get_execution_history`, `get_screenshot`, `get_trace_information` | viewer |
| Browser inspection | `get_browser_status`, `capture_page_screenshot`, `get_page_information`, `inspect_page_elements` | viewer |
| Session | `get_server_info` (your identity, role, allowed tools) | viewer |

Run tools accept `environment`, `browser`, `threads` (1–8) and `retries` (0–2). Reporting tools
accept `execution_id`: an id, `latest` (default) or `workspace`, which reads the project's
current `target/` (for example a run started outside MCP, or CI artifacts unpacked there).

**Browser inspection is not test execution.** It opens one page in a throwaway headless Chromium
to help write locators. Only the application's configured origins can be opened, and requests to
any other origin are blocked. It never signs in.

**AI tools** (`analyze_scenario_failure`, `plan_test_execution`, `generate_feature_file`,
`generate_step_definitions`, `suggest_regression_tests`, `suggest_missing_scenarios`,
`summarize_execution` and 7 supporting tools) are described in [AI.md](AI.md). All of them work
without a model; model calls need the runner role, and accepting generated files needs admin.

## Security controls

| Requirement | Implementation |
|---|---|
| Authentication | HTTP: bearer tokens, stored only as SHA-256 hashes and compared in constant time; failures are audited. stdio: the OS user who launched the process |
| Authorisation, tool-level permissions | Each tool declares a minimum role; `GuardedTools` checks it before the handler runs; tool descriptions state the role |
| Read-only by default | stdio callers are viewers unless configured otherwise; tools carry MCP `readOnlyHint` annotations |
| Approval for sensitive execution | Runs on `approval.envs` wait in `AWAITING_APPROVAL` until an **admin who is not the requester** approves; decisions and notes are audited |
| Destructive operations | None are exposed. The only state change besides starting runs is `cancel_execution` (requester or admin only) |
| Environment restrictions | Only `local`, `qa` and `staging` exist and `allowed.envs` narrows them; anything else is rejected |
| Input validation | Closed JSON schemas (unknown arguments are rejected); allow-list patterns for tag expressions, suite files, ids and URLs; tag expressions are checked token by token and by Cucumber's parser; scenario names become anchored, quoted regexes; the selection must match at least one scenario |
| No unrestricted shell | The only program ever started is `mvn`/`mvnw`, with a fixed argument list and no shell; the server's own `QA_MCP_*` variables are removed from its environment |
| Timeouts | Per-run timeout that stops Maven, the forked test JVM and browsers; 90 s MCP request timeout; 20 s browser-tool timeout |
| Audit logging | `<state>/audit.log` (JSON lines): every tool call with caller, role, outcome, redacted arguments and duration, plus authentication failures and approval decisions |
| Secret hygiene | Every string in tool output is redacted (bearer tokens, MCP tokens, password, secret and token fields, Luhn-valid card numbers); internal errors return an incident id, never a stack trace |
| Untrusted content | Server instructions tell clients that feature files, logs and reports are data, not instructions |

## Verification

All results are in [IMPLEMENTATION_STATUS.md](../IMPLEMENTATION_STATUS.md).

```bash
mvn -f mcp-server/pom.xml package
```

This runs Checkstyle and 34 unit and in-process HTTP tests, then builds the jar.

```bash
mvn -f mcp-server/pom.xml test -Dtest=StdioFrameworkIT
```

This needs the demo app on port 8000. An MCP client launches the jar over stdio and runs the
real API suite through it.
