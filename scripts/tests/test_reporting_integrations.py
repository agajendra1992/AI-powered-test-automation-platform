"""Tests for scripts/notify.py and scripts/testmgmt_export.py (no network: a fake opener records requests).

    python3 -m unittest discover -s scripts/tests -v
"""
import base64
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import notify  # noqa: E402
import testmgmt_export  # noqa: E402


class FakeResponse:
    def __init__(self, status=200, body=""):
        self.status = status
        self._body = body.encode()

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeOpener:
    """Records requests; answers from a list of bodies in order."""

    def __init__(self, *bodies):
        self.requests = []
        self.bodies = list(bodies) or [""]

    def __call__(self, request, timeout=None):
        self.requests.append(request)
        return FakeResponse(200, self.bodies.pop(0) if self.bodies else "")


def write_json(tmp, name, data):
    path = Path(tmp) / name
    path.write_text(json.dumps(data), encoding="utf-8")
    return str(path)


def step(status, error=None):
    result = {"status": status}
    if error:
        result["error_message"] = error
    return {"name": "a step", "result": result}


def cucumber(*scenarios):
    elements = [{"type": "background", "steps": [step("passed")]}]
    for i, (name, tags, statuses) in enumerate(scenarios):
        elements.append({"type": "scenario", "id": f"s{i}", "line": 10 + i, "name": name,
                         "tags": [{"name": t} for t in tags], "steps": [step(s) for s in statuses]})
    return [{"uri": "features/login.feature", "tags": [{"name": "@login"}], "elements": elements}]


def run(main, argv, env, opener=None):
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        code = main(argv, env=env, opener=opener or FakeOpener())
    return code, out.getvalue(), err.getvalue()


SUMMARY_A = {"suite": "Regression", "browser": "chromium", "total": 10, "passed": 9, "failed": 1, "skipped": 0,
             "flaky": 1, "flakyScenarios": ["Sign out"], "durationMs": 40000, "failuresByType": {"ASSERTION": 1}}
SUMMARY_B = {"suite": "Regression", "browser": "firefox", "total": 10, "passed": 10, "failed": 0, "skipped": 0,
             "flaky": 0, "durationMs": 55000}


class NotifyTest(unittest.TestCase):

    def test_aggregates_shards_and_reports_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            s = notify.aggregate([write_json(tmp, "a.json", SUMMARY_A), write_json(tmp, "b.json", SUMMARY_B)])
        self.assertEqual((s["total"], s["passed"], s["failed"]), (20, 19, 1))
        self.assertEqual(s["durationMs"], 55000)
        self.assertEqual(notify.status(s), "FAILED")
        self.assertEqual(s["browsers"], {"chromium", "firefox"})

    def test_no_summaries_is_not_reported_as_passed(self):
        self.assertEqual(notify.status(notify.aggregate([])), "NO RESULTS")

    def test_not_configured_sends_nothing_and_succeeds(self):
        opener = FakeOpener()
        code, out, _ = run(notify.main, [], {}, opener)
        self.assertEqual(code, 0)
        self.assertIn("not configured", out)
        self.assertEqual(opener.requests, [])

    def test_sends_slack_and_teams_without_printing_urls(self):
        secret_slack = "https://hooks.slack.com/services/T000/B000/SECRETPART"
        secret_teams = "https://prod.westus.logic.azure.com/workflows/abc/triggers/manual?sig=SECRETSIG"
        opener = FakeOpener()
        with tempfile.TemporaryDirectory() as tmp:
            code, out, err = run(notify.main, [write_json(tmp, "a.json", SUMMARY_A)],
                                 {"SLACK_WEBHOOK_URL": secret_slack, "TEAMS_WEBHOOK_URL": secret_teams,
                                  "RUN_TITLE": "Regression", "RUN_URL": "https://github.com/o/r/actions/runs/1"}, opener)
        self.assertEqual(code, 0)
        self.assertEqual([r.full_url for r in opener.requests], [secret_slack, secret_teams])
        self.assertNotIn("SECRET", out + err)
        slack = json.loads(opener.requests[0].data)
        self.assertIn("FAILED: Regression", slack["text"])
        self.assertIn("9 passed, 1 failed", slack["blocks"][0]["text"]["text"])
        teams = json.loads(opener.requests[1].data)
        self.assertEqual(teams["attachments"][0]["content"]["type"], "AdaptiveCard")

    def test_rejects_non_slack_host_and_plain_http(self):
        opener = FakeOpener()
        code, _, err = run(notify.main, [], {"SLACK_WEBHOOK_URL": "https://evil.example.com/hook",
                                             "TEAMS_WEBHOOK_URL": "http://teams.example.com/hook"}, opener)
        self.assertEqual(code, 0)
        self.assertEqual(opener.requests, [])
        self.assertIn("hooks.slack.com", err)

    def test_only_on_failure_skips_green_runs(self):
        opener = FakeOpener()
        with tempfile.TemporaryDirectory() as tmp:
            code, out, _ = run(notify.main, ["--only-on-failure", write_json(tmp, "b.json", SUMMARY_B)],
                               {"SLACK_WEBHOOK_URL": "https://hooks.slack.com/services/x"}, opener)
        self.assertEqual(opener.requests, [])
        self.assertIn("no notification", out)


class TestRailTest(unittest.TestCase):

    def test_maps_case_tags_and_worst_outcome_wins(self):
        features = cucumber(("Sign in", ["@C101"], ["passed"]),
                            ("Refused row 1", ["@C102"], ["passed"]),
                            ("Refused row 2", ["@C102"], ["failed"]),
                            ("No tag", [], ["passed"]),
                            ("Skipped", ["@C103"], ["skipped"]))
        results, unmapped = testmgmt_export.testrail_results(features, "")
        self.assertEqual([(r["case_id"], r["status_id"]) for r in results], [(101, 1), (102, 5)])
        self.assertEqual(len(unmapped), 1)

    def test_failed_hook_fails_the_scenario(self):
        element = {"steps": [step("passed")], "after": [{"result": {"status": "failed"}}]}
        self.assertEqual(testmgmt_export.scenario_status(element), "failed")

    def test_retried_scenario_keeps_last_attempt(self):
        first = cucumber(("Flaky", ["@C5"], ["failed"]))
        second = cucumber(("Flaky", ["@C5"], ["passed"]))
        results, _ = testmgmt_export.testrail_results(first + second, "")
        self.assertEqual(results[0]["status_id"], 1)

    def test_without_credentials_is_a_dry_run(self):
        opener = FakeOpener()
        with tempfile.TemporaryDirectory() as tmp:
            code, out, _ = run(testmgmt_export.main, ["testrail", write_json(tmp, "c.json", cucumber(("A", ["@C1"], ["passed"])))],
                               {}, opener)
        self.assertEqual(code, 0)
        self.assertEqual(opener.requests, [])
        self.assertIn("Dry run", out)

    def test_sends_results_with_basic_auth_and_hides_key(self):
        opener = FakeOpener("[]")
        env = {"TESTRAIL_URL": "https://acme.testrail.io", "TESTRAIL_USER": "qa@example.com",
               "TESTRAIL_API_KEY": "SECRETKEY", "TESTRAIL_RUN_ID": "42"}
        with tempfile.TemporaryDirectory() as tmp:
            code, out, err = run(testmgmt_export.main,
                                 ["testrail", write_json(tmp, "c.json", cucumber(("A", ["@C1"], ["failed"])))], env, opener)
        self.assertEqual(code, 0)
        request = opener.requests[0]
        self.assertEqual(request.full_url, "https://acme.testrail.io/index.php?/api/v2/add_results_for_cases/42")
        self.assertEqual(request.get_header("Authorization"),
                         "Basic " + base64.b64encode(b"qa@example.com:SECRETKEY").decode())
        self.assertEqual(json.loads(request.data)["results"][0]["status_id"], 5)
        self.assertNotIn("SECRETKEY", out + err)

    def test_rejects_non_numeric_run_id(self):
        opener = FakeOpener()
        env = {"TESTRAIL_URL": "https://acme.testrail.io", "TESTRAIL_USER": "u", "TESTRAIL_API_KEY": "k",
               "TESTRAIL_RUN_ID": "42; drop"}
        with tempfile.TemporaryDirectory() as tmp:
            code, _, err = run(testmgmt_export.main, ["testrail", "--strict",
                                                      write_json(tmp, "c.json", cucumber(("A", ["@C1"], ["passed"])))], env, opener)
        self.assertEqual(code, 1)
        self.assertEqual(opener.requests, [])


class XrayTest(unittest.TestCase):

    def test_authenticates_then_imports_cucumber_json(self):
        opener = FakeOpener('"TOKEN123"', '{"id": "1", "key": "QA-900"}')
        env = {"XRAY_CLIENT_ID": "id", "XRAY_CLIENT_SECRET": "SECRET"}
        features = cucumber(("Sign in", ["@QA-12"], ["passed"]))
        with tempfile.TemporaryDirectory() as tmp:
            code, out, err = run(testmgmt_export.main, ["xray", write_json(tmp, "c.json", features)], env, opener)
        self.assertEqual(code, 0)
        auth, upload = opener.requests
        self.assertEqual(auth.full_url, "https://xray.cloud.getxray.app/api/v2/authenticate")
        self.assertEqual(upload.full_url, "https://xray.cloud.getxray.app/api/v2/import/execution/cucumber")
        self.assertEqual(upload.get_header("Authorization"), "Bearer TOKEN123")
        self.assertEqual(json.loads(upload.data), features)
        self.assertIn("QA-900", out)
        self.assertNotIn("SECRET", out + err)
        self.assertNotIn("TOKEN123", out + err)

    def test_counts_jira_key_tags(self):
        mapped, unmapped = testmgmt_export.xray_summary(cucumber(("A", ["@QA-1"], ["passed"]), ("B", ["@smoke"], ["passed"])))
        self.assertEqual((mapped, len(unmapped)), (1, 1))


if __name__ == "__main__":
    unittest.main()
