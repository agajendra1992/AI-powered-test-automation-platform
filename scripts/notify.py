#!/usr/bin/env python3
"""Post a test-run summary to Slack and/or Microsoft Teams.

Reads the execution-summary.json files of a run (one per shard or browser), adds them up and sends
one short message: pass/fail counts, flaky scenarios, duration and a link to the run.

Targets are configured only through secrets in the environment; with none set the script prints
"not configured" and exits 0, so it never blocks a pipeline:

    SLACK_WEBHOOK_URL   Slack incoming webhook (https://hooks.slack.com/...)
    TEAMS_WEBHOOK_URL   Teams "Workflows" webhook (Power Automate, https://...)

Context (set by GitHub Actions): RUN_TITLE, RUN_URL, GIT_REF.

    python3 scripts/notify.py summaries/*/execution-summary.json
    python3 scripts/notify.py --dry-run summaries/*/execution-summary.json   # print payloads, send nothing

Webhook URLs are secrets: they are never printed, and only https URLs are accepted (Slack: hooks.slack.com).
Standard library only.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

MAX_LISTED = 10
TIMEOUT_S = 15


def aggregate(paths: list[str]) -> dict:
    """Adds up shard/browser summaries. Unreadable files are reported and skipped."""
    total = {"total": 0, "passed": 0, "failed": 0, "skipped": 0, "flaky": 0, "durationMs": 0,
             "browsers": set(), "suites": set(), "flakyScenarios": [], "failuresByType": {}, "files": 0}
    for raw in paths:
        try:
            data = json.loads(Path(raw).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            print(f"Skipped unreadable summary {raw}: {error.__class__.__name__}", file=sys.stderr)
            continue
        total["files"] += 1
        for key in ("total", "passed", "failed", "skipped", "flaky"):
            total[key] += int(data.get(key, 0) or 0)
        # Shards run in parallel: the run takes as long as the slowest one.
        total["durationMs"] = max(total["durationMs"], int(data.get("durationMs", 0) or 0))
        total["browsers"].add(str(data.get("browser", "?")))
        total["suites"].add(str(data.get("suite", "?")))
        total["flakyScenarios"].extend(str(s) for s in data.get("flakyScenarios", []) or [])
        for kind, count in (data.get("failuresByType") or {}).items():
            total["failuresByType"][kind] = total["failuresByType"].get(kind, 0) + int(count)
    return total


def status(summary: dict) -> str:
    if summary["files"] == 0:
        return "NO RESULTS"
    return "FAILED" if summary["failed"] > 0 else "PASSED"


def headline(summary: dict, title: str) -> str:
    return f"{status(summary)}: {title}"


def facts(summary: dict, ref: str) -> list[tuple[str, str]]:
    rows = [
        ("Result", f"{summary['passed']} passed, {summary['failed']} failed, {summary['skipped']} skipped "
                   f"of {summary['total']}"),
        ("Suites", ", ".join(sorted(summary["suites"])) or "-"),
        ("Browsers", ", ".join(sorted(summary["browsers"])) or "-"),
        ("Duration", f"{summary['durationMs'] / 1000:.0f} s (slowest shard)"),
        ("Branch", ref or "-"),
    ]
    if summary["flaky"]:
        listed = summary["flakyScenarios"][:MAX_LISTED]
        rows.append(("Flaky", f"{summary['flaky']}: " + "; ".join(listed)))
    if summary["failuresByType"]:
        rows.append(("Failure types", ", ".join(f"{k} {v}" for k, v in sorted(summary["failuresByType"].items()))))
    return rows


def slack_payload(summary: dict, title: str, run_url: str, ref: str) -> dict:
    lines = [f"*{headline(summary, title)}*"] + [f"*{k}:* {v}" for k, v in facts(summary, ref)]
    if run_url:
        lines.append(f"<{run_url}|Open the run and the Allure report>")
    text = "\n".join(lines)
    return {"text": headline(summary, title), "blocks": [{"type": "section", "text": {"type": "mrkdwn", "text": text}}]}


def teams_payload(summary: dict, title: str, run_url: str, ref: str) -> dict:
    colour = {"PASSED": "Good", "FAILED": "Attention"}.get(status(summary), "Warning")
    card = {
        "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
        "type": "AdaptiveCard",
        "version": "1.4",
        "body": [
            {"type": "TextBlock", "text": headline(summary, title), "weight": "Bolder", "size": "Medium", "color": colour, "wrap": True},
            {"type": "FactSet", "facts": [{"title": k, "value": v} for k, v in facts(summary, ref)]},
        ],
    }
    if run_url:
        card["actions"] = [{"type": "Action.OpenUrl", "title": "Open the run", "url": run_url}]
    return {"type": "message", "attachments": [{"contentType": "application/vnd.microsoft.card.adaptive", "content": card}]}


def valid_webhook(name: str, url: str) -> bool:
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname:
        print(f"{name}: not an https URL; not sent", file=sys.stderr)
        return False
    if name == "Slack" and parsed.hostname != "hooks.slack.com":
        print("Slack: the webhook must be on hooks.slack.com; not sent", file=sys.stderr)
        return False
    return True


def post(name: str, url: str, payload: dict, opener=urllib.request.urlopen) -> bool:
    """Sends one payload. Errors are reported (without the URL) and never raise: a notification must not fail the run."""
    request = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"),
                                     headers={"Content-Type": "application/json"}, method="POST")
    try:
        with opener(request, timeout=TIMEOUT_S) as response:
            print(f"{name}: sent (HTTP {response.status})")
            return True
    except urllib.error.HTTPError as error:
        print(f"{name}: rejected with HTTP {error.code}", file=sys.stderr)
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        print(f"{name}: not delivered ({error.__class__.__name__})", file=sys.stderr)
    return False


def main(argv: list[str] | None = None, env: dict | None = None, opener=urllib.request.urlopen) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("summaries", nargs="*", help="execution-summary.json files")
    parser.add_argument("--dry-run", action="store_true", help="print the payloads instead of sending them")
    parser.add_argument("--only-on-failure", action="store_true", help="send nothing when every scenario passed")
    args = parser.parse_args(argv)
    env = os.environ if env is None else env

    summary = aggregate(args.summaries)
    title = env.get("RUN_TITLE", "Test run")
    run_url = env.get("RUN_URL", "")
    ref = env.get("GIT_REF", "")
    targets = [(name, env.get(var, "").strip(), build) for name, var, build in
               (("Slack", "SLACK_WEBHOOK_URL", slack_payload), ("Teams", "TEAMS_WEBHOOK_URL", teams_payload))]

    if args.only_on_failure and status(summary) == "PASSED":
        print("All scenarios passed; --only-on-failure, so no notification.")
        return 0
    if args.dry_run:
        for name, _, build in targets:
            print(f"--- {name} payload\n{json.dumps(build(summary, title, run_url, ref), indent=2)}")
        return 0
    configured = [(n, u, b) for n, u, b in targets if u]
    if not configured:
        print("Notifications not configured (no SLACK_WEBHOOK_URL or TEAMS_WEBHOOK_URL); nothing sent.")
        return 0
    for name, url, build in configured:
        if valid_webhook(name, url):
            post(name, url, build(summary, title, run_url, ref), opener)
    return 0


if __name__ == "__main__":
    sys.exit(main())
