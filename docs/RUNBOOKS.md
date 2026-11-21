# Operational runbooks

Each entry covers the symptom, how to confirm it, and the fix. Tools named in `code` are MCP tools.

## Every scenario fails at "the application is available"
* **Confirm:** `analyze_scenario_failure` says ENVIRONMENT_FAILURE ("health check failed"), and
  `correlate_failures` shows one cluster across many scenarios.
* **Fix:** the application under test is down or the URL is wrong. Check `base.url`/`api.base.url`
  for the environment and the application's own health. Rerun when it is back; these failures are
  not test defects.

## Many failures with one signature in one run
* **Confirm:** `correlate_failures` reports "N scenarios failed the same way in one run".
* **Fix:** look for a shared cause: test data (TEST_DATA_ISSUE), a credential (AUTHENTICATION), a
  shared step or page object (LOCATOR, after a UI change). Fix the cause once, not each scenario.

## A scenario is flaky
* **Confirm:** `get_flaky_tests` shows FLAKY (passes after retry, or alternates).
* **Fix:** `analyze_scenario_failure` on a failing run; usual causes are missing waits for a
  response, shared data between parallel scenarios, or animations. Fix it in the page object or
  step. Quarantine (`@quarantine`) only with a ticket and an owner: a quarantined scenario stops
  running everywhere.

## A scenario fails consistently
* **Confirm:** `get_flaky_tests` shows CONSISTENTLY_FAILING.
* **Fix:** treat it as a defect or a broken test. Don't retry or quarantine it to make the
  build green.

## An MCP run is stuck or slow
* **Confirm:** `get_execution_status` shows RUNNING for longer than usual; `qa_mcp_queue_length` is growing.
* **Fix:** `cancel_execution` stops Maven, the test JVM and the browsers. Runs also stop at the
  timeout (`QA_MCP_RUN_TIMEOUT_MINUTES`, status TIMED_OUT). Check the console tail in
  `get_execution_status`, and the environment's speed.

## An MCP run ends with ERROR
* **Confirm:** `get_execution_status` shows "build failed before tests produced a summary" and a console tail.
* **Fix:** a compile error, a Maven or network problem, or no browsers. Run the same suite locally
  (`mvn test -DsuiteXmlFile=…`) to reproduce. ERROR is never a test result.

## A run waits for approval
* **Confirm:** status AWAITING_APPROVAL.
* **Fix:** an admin other than the requester calls `approve_execution` (or `reject_execution` with
  a reason). Approvals are audited.

## 401 from MCP or RAG
* **Fix:** the token or key is missing, wrong or revoked. Check the hash entry in the tokens or keys
  file (only hashes are stored). Restart the service after editing the file. Rising
  `qa_mcp_auth_failures_total` without a known cause means someone is guessing: rotate the tokens.

## Rotate tokens or keys
1. Create a new token (`java -jar qa-mcp-server.jar new-token <name> <role>` or `scripts/dev-secrets.sh`).
2. Add the new hash, deploy, and switch clients to the new token.
3. Remove the old hash and redeploy. The audit logs show whether the old token is still in use.

## RAG returns nothing or old content
* **Confirm:** `GET /rag/health` (chunk count), `GET /rag/documents`.
* **Fix:** re-index: `ingest_project_knowledge` (incremental) and `ingest_execution_results` for
  recent runs. "Index was built with …" at start-up means the embedder changed: use a new data
  directory or volume and re-index.

## AI tools say "budget" or "unavailable"
* **Confirm:** `get_ai_status`, `get_ai_usage`.
* **Fix:** the budget resets at 00:00 UTC (`QA_AI_DAILY_TOKEN_BUDGET`). For "unavailable", check
  the provider key and model. Every AI tool still returns its deterministic result, and test runs
  are not affected.

## Disk use
* The `mcp-state` volume keeps each run's results (including screenshots and traces). Remove
  old `executions/<id>` directories as policy dictates; the audit log should be archived, not deleted.
