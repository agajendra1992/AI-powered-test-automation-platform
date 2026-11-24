#!/usr/bin/env python3
"""Send Cucumber results to a test-management tool: Jira Xray (Cloud) or TestRail.

Scenarios are linked to test cases by tags in the feature files:

    Xray      @QA-123      (Jira issue key of the Xray Test; Xray's own Cucumber convention)
    TestRail  @C1234       (TestRail case id)

    python3 scripts/testmgmt_export.py xray     target/cucumber-reports/*.json
    python3 scripts/testmgmt_export.py testrail target/cucumber-reports/*.json
    python3 scripts/testmgmt_export.py testrail --dry-run target/cucumber-reports/*.json

Credentials come only from the environment (CI secrets). With them missing the command does a dry run:
it prints what would be sent, sends nothing and exits 0, so it never blocks a pipeline.

    Xray Cloud   XRAY_CLIENT_ID, XRAY_CLIENT_SECRET, optional XRAY_BASE_URL (default https://xray.cloud.getxray.app;
                 EU: https://eu.xray.cloud.getxray.app), optional XRAY_PROJECT_KEY (only for the summary)
    TestRail     TESTRAIL_URL (https://<org>.testrail.io), TESTRAIL_USER, TESTRAIL_API_KEY, TESTRAIL_RUN_ID

Results go to the tool exactly as the run produced them: a failed scenario is never reported as passed.
Tokens, keys and URLs with credentials are never printed. Standard library only.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

TIMEOUT_S = 30
XRAY_DEFAULT = "https://xray.cloud.getxray.app"
TESTRAIL_CASE = re.compile(r"^@C(\d+)$")
JIRA_KEY = re.compile(r"^@([A-Z][A-Z0-9_]+-\d+)$")
# TestRail status ids: 1 passed, 5 failed. Skipped scenarios are not sent (TestRail has no "skipped" result).
TESTRAIL_STATUS = {"passed": 1, "failed": 5}
SEVERITY = {"failed": 2, "skipped": 1, "passed": 0}


def load_features(paths: list[str]) -> list[dict]:
    features: list[dict] = []
    for raw in paths:
        try:
            data = json.loads(Path(raw).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            print(f"Skipped unreadable Cucumber report {raw}: {error.__class__.__name__}", file=sys.stderr)
            continue
        if isinstance(data, list):
            features.extend(f for f in data if isinstance(f, dict))
    return features


def scenario_status(element: dict) -> str:
    """failed if any step or hook failed; skipped if any step did not run; otherwise passed."""
    results = [s.get("result", {}).get("status", "") for s in element.get("steps", [])]
    results += [h.get("result", {}).get("status", "") for key in ("before", "after") for h in element.get(key, [])]
    if any(r == "failed" for r in results):
        return "failed"
    if not results or any(r in ("skipped", "pending", "undefined", "ambiguous", "") for r in results):
        return "skipped"
    return "passed"


def scenarios(features: list[dict]) -> list[dict]:
    """One entry per scenario (outline rows separately). A retried scenario keeps its last attempt."""
    latest: dict[tuple, dict] = {}
    for feature in features:
        feature_tags = [t.get("name", "") for t in feature.get("tags", [])]
        for element in feature.get("elements", []):
            if element.get("type") != "scenario":
                continue
            tags = feature_tags + [t.get("name", "") for t in element.get("tags", [])]
            key = (feature.get("uri"), element.get("line"), element.get("id"))
            latest[key] = {"name": element.get("name", ""), "uri": feature.get("uri", ""), "line": element.get("line"),
                           "tags": tags, "status": scenario_status(element)}
    return list(latest.values())


def first_error(element_steps: list[dict]) -> str:
    for step in element_steps:
        message = step.get("result", {}).get("error_message")
        if message:
            return message.splitlines()[0][:300]
    return ""


# ----------------------------------------------------------------------------- TestRail
def testrail_results(features: list[dict], comment: str) -> tuple[list[dict], list[str]]:
    """Results per case id. Several scenarios on one case (outline rows): the worst outcome wins."""
    by_case: dict[int, dict] = {}
    unmapped: list[str] = []
    for s in scenarios(features):
        ids = [int(m.group(1)) for t in s["tags"] if (m := TESTRAIL_CASE.match(t))]
        if not ids:
            unmapped.append(f"{s['uri']}:{s['line']} {s['name']}")
            continue
        for case_id in ids:
            current = by_case.get(case_id)
            if current is None or SEVERITY[s["status"]] > SEVERITY[current["status"]]:
                by_case[case_id] = {"case_id": case_id, "status": s["status"], "scenario": s["name"]}
    results = []
    for case_id, entry in sorted(by_case.items()):
        if entry["status"] not in TESTRAIL_STATUS:
            continue
        text = f"{entry['scenario']}: {entry['status']}" + (f"\n{comment}" if comment else "")
        results.append({"case_id": case_id, "status_id": TESTRAIL_STATUS[entry["status"]], "comment": text})
    return results, unmapped


def send_testrail(results: list[dict], env: dict, opener) -> bool:
    base = env["TESTRAIL_URL"].rstrip("/")
    if urlparse(base).scheme != "https":
        print("TestRail: TESTRAIL_URL must be https; nothing sent", file=sys.stderr)
        return False
    run_id = env["TESTRAIL_RUN_ID"].strip()
    if not run_id.isdigit():
        print("TestRail: TESTRAIL_RUN_ID must be a number; nothing sent", file=sys.stderr)
        return False
    auth = base64.b64encode(f"{env['TESTRAIL_USER']}:{env['TESTRAIL_API_KEY']}".encode()).decode()
    request = urllib.request.Request(f"{base}/index.php?/api/v2/add_results_for_cases/{run_id}",
                                     data=json.dumps({"results": results}).encode("utf-8"), method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": f"Basic {auth}"})
    return call("TestRail", request, opener) is not None


# ----------------------------------------------------------------------------- Xray
def xray_summary(features: list[dict]) -> tuple[int, list[str]]:
    mapped, unmapped = 0, []
    for s in scenarios(features):
        if any(JIRA_KEY.match(t) for t in s["tags"]):
            mapped += 1
        else:
            unmapped.append(f"{s['uri']}:{s['line']} {s['name']}")
    return mapped, unmapped


def send_xray(features: list[dict], env: dict, opener) -> bool:
    base = env.get("XRAY_BASE_URL", "").strip().rstrip("/") or XRAY_DEFAULT
    if urlparse(base).scheme != "https":
        print("Xray: XRAY_BASE_URL must be https; nothing sent", file=sys.stderr)
        return False
    auth = urllib.request.Request(f"{base}/api/v2/authenticate", method="POST",
                                  data=json.dumps({"client_id": env["XRAY_CLIENT_ID"],
                                                   "client_secret": env["XRAY_CLIENT_SECRET"]}).encode("utf-8"),
                                  headers={"Content-Type": "application/json"})
    token_body = call("Xray authentication", auth, opener)
    if token_body is None:
        return False
    token = json.loads(token_body)
    request = urllib.request.Request(f"{base}/api/v2/import/execution/cucumber", method="POST",
                                     data=json.dumps(features).encode("utf-8"),
                                     headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"})
    body = call("Xray import", request, opener)
    if body is None:
        return False
    key = json.loads(body).get("key", "?")
    print(f"Xray: test execution {key} created")
    return True


# ----------------------------------------------------------------------------- shared
def call(name: str, request: urllib.request.Request, opener) -> str | None:
    """Performs one request; reports failures by status only (bodies may echo credentials)."""
    try:
        with opener(request, timeout=TIMEOUT_S) as response:
            print(f"{name}: HTTP {response.status}")
            return response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        print(f"{name}: rejected with HTTP {error.code}", file=sys.stderr)
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        print(f"{name}: not delivered ({error.__class__.__name__})", file=sys.stderr)
    return None


def report_unmapped(unmapped: list[str]) -> None:
    if unmapped:
        print(f"{len(unmapped)} scenario(s) have no test-case tag and were not sent, e.g.:")
        for line in unmapped[:5]:
            print(f"  - {line}")


def main(argv: list[str] | None = None, env: dict | None = None, opener=urllib.request.urlopen) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("tool", choices=["xray", "testrail"])
    parser.add_argument("reports", nargs="*", help="Cucumber JSON report files")
    parser.add_argument("--dry-run", action="store_true", help="show what would be sent; send nothing")
    parser.add_argument("--strict", action="store_true", help="exit 1 when sending fails (default: warn only)")
    args = parser.parse_args(argv)
    env = os.environ if env is None else env

    features = load_features(args.reports)
    if not features:
        print("No Cucumber results found; nothing to send.")
        return 0

    if args.tool == "testrail":
        results, unmapped = testrail_results(features, env.get("RUN_URL", ""))
        passed = sum(r["status_id"] == 1 for r in results)
        print(f"TestRail: {len(results)} case result(s) ({passed} passed, {len(results) - passed} failed)")
        report_unmapped(unmapped)
        needed = ("TESTRAIL_URL", "TESTRAIL_USER", "TESTRAIL_API_KEY", "TESTRAIL_RUN_ID")
        if args.dry_run or not all(env.get(k, "").strip() for k in needed):
            print("Dry run, nothing sent:" if args.dry_run else "Dry run, nothing sent (set " + ", ".join(needed) + " to send):")
            print(json.dumps({"results": results}, indent=2))
            return 0
        if not results:
            return 0
        ok = send_testrail(results, env, opener)
    else:
        mapped, unmapped = xray_summary(features)
        print(f"Xray: {mapped} scenario(s) tagged with a Jira test key")
        report_unmapped(unmapped)
        needed = ("XRAY_CLIENT_ID", "XRAY_CLIENT_SECRET")
        if args.dry_run or not all(env.get(k, "").strip() for k in needed):
            print("Dry run: set " + ", ".join(needed) + " to import the Cucumber results into Xray.")
            return 0
        ok = send_xray(features, env, opener)
    return 1 if args.strict and not ok else 0


if __name__ == "__main__":
    sys.exit(main())
