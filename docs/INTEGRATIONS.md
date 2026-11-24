# Notifications and test-management integration

The report job (`report.yml`, used by the nightly regression and the weekly cross-browser run) can
post a summary to **Slack** or **Microsoft Teams** and send results to **Jira Xray** or **TestRail**.
Each integration is off until its secrets are set; until then the job prints "not configured" or does
a dry run, and never fails because of it.

Results are sent as the run produced them: a failed scenario is never reported as passed, and a
delivery failure only prints a warning (the test outcome is decided by the tests, not by the integration).

## Slack / Teams (`scripts/notify.py`)

Message: PASSED / FAILED / NO RESULTS, counts (passed, failed, skipped), suites, browsers, duration,
branch, flaky scenarios, failure types, and a link to the run (where the Allure report is).

| Secret | Value |
|---|---|
| `SLACK_WEBHOOK_URL` | Slack app → Incoming Webhooks → channel webhook (`https://hooks.slack.com/...` only) |
| `TEAMS_WEBHOOK_URL` | Teams channel → Workflows → "Post to a channel when a webhook request is received" (https only; Adaptive Card payload) |

```bash
python3 scripts/notify.py --dry-run target/execution-summary.json     # see both payloads
python3 scripts/notify.py --only-on-failure summaries/*/execution-summary.json
```

## Test management (`scripts/testmgmt_export.py`)

Scenarios are linked to test cases by tags in the feature files. Untagged scenarios are listed and skipped.

| Tool | Tag | Secrets (Settings → Secrets) | Variables (Settings → Variables) |
|---|---|---|---|
| Xray Cloud | `@QA-123` (Jira key of the Xray Test) | `XRAY_CLIENT_ID`, `XRAY_CLIENT_SECRET` | optional `XRAY_BASE_URL` (EU/US/AU region) |
| TestRail | `@C1234` (case id) | `TESTRAIL_API_KEY` | `TESTRAIL_URL`, `TESTRAIL_USER`, `TESTRAIL_RUN_ID` |

* **Xray**: authenticates with the API key pair and imports the merged Cucumber JSON
  (`/api/v2/import/execution/cucumber`); Xray creates a Test Execution and prints its key.
* **TestRail**: `add_results_for_cases` on the configured run. Passed → *Passed*, failed (a step or hook) →
  *Failed*; skipped scenarios are not sent. A case covered by several scenarios (outline rows) gets the
  worst outcome; a retried scenario counts with its last attempt.

```bash
python3 scripts/testmgmt_export.py testrail --dry-run target/cucumber-reports/regression.json
python3 scripts/testmgmt_export.py xray target/cucumber-reports/*.json
```

No test-case tags are in the feature files yet: they depend on your Jira project or TestRail suite.

## Security

* Webhook URLs, keys and tokens come only from secrets and are never printed; errors show the HTTP
  status only (response bodies can echo credentials).
* The report job receives only these five secrets, not every repository secret.
* Only https endpoints are accepted; Slack webhooks must be on `hooks.slack.com`. The TestRail run id
  must be a number.
* Messages contain counts, scenario names and the run link: no request data, tokens or test data.

## Verification (2026-11-24)

* `scripts/tests`: 14 unit tests pass (aggregation, payloads, the no-config path, host checks, TestRail mapping
  and auth, Xray auth then import, no secrets in output), with a fake HTTP opener: **no live Slack, Teams,
  Xray or TestRail account was used**.
* Dry runs against the real regression output (46 scenarios) produce the expected payloads.
