#!/usr/bin/env python3
"""Render one or more target/execution-summary.json files as a Markdown table.

Used by the GitHub Actions workflows to write the job summary ($GITHUB_STEP_SUMMARY), and by the
report job to aggregate shards. Standard library only. Reads only the summary fields, which never
contain credentials.

    python3 scripts/ci_summary.py target/execution-summary.json
    python3 scripts/ci_summary.py summaries/*/execution-summary.json
"""
import json
import sys
from pathlib import Path

COUNTS = ("total", "passed", "failed", "skipped", "flaky", "retriedAttempts")


def load(paths):
    rows = []
    for raw in paths:
        path = Path(raw)
        if not path.is_file():
            continue
        try:
            rows.append((path, json.loads(path.read_text(encoding="utf-8"))))
        except (OSError, json.JSONDecodeError) as error:
            print(f"> Skipped unreadable summary `{path}`: {error.__class__.__name__}", file=sys.stderr)
    return rows


def cell(value):
    return str(value).replace("|", "\\|").replace("\n", " ")


def render(rows):
    if not rows:
        return "### Test execution\n\nNo execution summary was produced (the run failed before tests started).\n"
    out = ["### Test execution", "",
           "| Suite | Browser | Env | Shard | Total | Passed | Failed | Skipped | Flaky | Retries | Duration |",
           "|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|"]
    totals = dict.fromkeys(COUNTS, 0)
    flaky = []
    for path, s in rows:
        for key in COUNTS:
            totals[key] += int(s.get(key, 0) or 0)
        flaky.extend(s.get("flakyScenarios", []) or [])
        shard_total = int(s.get("shardTotal", 1) or 1)
        shard = f"{int(s.get('shardIndex', 0)) + 1}/{shard_total}" if shard_total > 1 else "-"
        out.append("| " + " | ".join(cell(v) for v in (
            s.get("suite", "?"), s.get("browser", "?"), s.get("environment", "?"), shard,
            s.get("total", 0), s.get("passed", 0), s.get("failed", 0), s.get("skipped", 0),
            s.get("flaky", 0), s.get("retriedAttempts", 0), f"{int(s.get('durationMs', 0)) / 1000:.1f}s")) + " |")
    if len(rows) > 1:
        out.append("| **All** | | | | **{total}** | **{passed}** | **{failed}** | **{skipped}** | **{flaky}** | "
                   "**{retriedAttempts}** | |".format(**totals))
    status = "❌ Failures" if totals["failed"] else "✅ All passed"
    out += ["", f"**{status}**: {totals['passed']}/{totals['total']} passed."]
    if flaky:
        out += ["", "Flaky (passed only on retry):", *[f"- `{cell(f)}`" for f in sorted(set(flaky))]]
    tag_filters = {s.get("tagFilter") for _, s in rows if s.get("tagFilter")}
    if tag_filters:
        out += ["", "Tag filter: " + ", ".join(f"`{cell(t)}`" for t in sorted(tag_filters))]
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    sys.stdout.write(render(load(sys.argv[1:])))
