#!/usr/bin/env python3
"""Execution history: how many test cases ran, passed and failed over time.

Every suite run (smoke, regression, UI, API, e2e) leaves a copy of its execution summary in the
history folder (`history.dir`, default `.qa-history/runs/`). This script turns those runs into one
report, however many runs a period had:

  * last 1, 3, 7, 10 and 30 days (calendar days, today included), each with a day-by-day breakdown
  * week by week (Monday to Sunday) and month by month
  * the test cases that failed or were flaky in the last 30 days, and the runs themselves

Standard library only. The summaries hold counts, scenario names and ids, never credentials.

    python3 scripts/execution_history.py report                    # -> target/execution-history/index.html
    python3 scripts/execution_history.py report --tz Asia/Kolkata --out reports/history.html
    python3 scripts/execution_history.py table >> "$GITHUB_STEP_SUMMARY"   # Markdown, for CI
    python3 scripts/execution_history.py record summaries/*/execution-summary.json --label "Regression #41"

`record` is for CI, where shards (or browsers) of one run each write a summary: it merges them into
one run in the history. Local runs are recorded by the framework itself (ExecutionListener).

Definitions used everywhere: executions = passed + failed + skipped (a scenario run 3 times in a
period counts 3 times); distinct test cases = different scenarios among them; pass rate = passed /
executions; flaky = failed first, passed on a retry (it is also counted as passed).
"""
import argparse
import html
import json
import os
import re
import sys
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

try:
    from zoneinfo import ZoneInfo
except ImportError:  # pragma: no cover - Python < 3.9
    ZoneInfo = None

DEFAULT_HISTORY = ".qa-history"
DEFAULT_OUT = "target/execution-history/index.html"
PERIODS = (1, 3, 7, 10, 30)
WEEKS = 12
MONTHS = 12
KEEP_DAYS = 400
COUNTS = ("total", "passed", "failed", "skipped", "flaky")


# ----------------------------------------------------------------------------------- loading

def parse_time(value):
    """ISO-8601 string -> aware UTC datetime, or None."""
    if not value or not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return (parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)).astimezone(timezone.utc)


def as_int(value):
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def normalise(raw, source=""):
    """A summary or merged run -> the fields the report uses. None if it has no usable time."""
    finished = parse_time(raw.get("finishedAt"))
    if finished is None:
        return None
    run = {key: as_int(raw.get(key)) for key in COUNTS}
    run["total"] = max(run["total"], run["passed"] + run["failed"] + run["skipped"])
    run.update(
        finished=finished,
        suite=str(raw.get("suite") or "?"),
        environment=str(raw.get("environment") or "?"),
        browser=str(raw.get("browser") or "?"),
        tagFilter=str(raw.get("tagFilter") or ""),
        durationMs=as_int(raw.get("durationMs")),
        label=str(raw.get("label") or ""),
        runUrl=str(raw.get("runUrl") or ""),
        source=source,
    )
    scenarios = raw.get("scenarios")
    run["scenarios"] = None
    if isinstance(scenarios, list):
        run["scenarios"] = [
            {"id": str(s.get("id") or s.get("name") or "?"), "name": str(s.get("name") or s.get("id") or "?"),
             "status": str(s.get("status") or "?"), "browser": str(s.get("browser") or run["browser"])}
            for s in scenarios if isinstance(s, dict)]
    return run


def load_runs(history_dir):
    runs = []
    folder = Path(history_dir) / "runs"
    if not folder.is_dir():
        return runs
    for path in sorted(folder.glob("*.json")):
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            print(f"Skipped unreadable run {path.name}: {error.__class__.__name__}", file=sys.stderr)
            continue
        run = normalise(raw, path.name) if isinstance(raw, dict) else None
        if run:
            runs.append(run)
    runs.sort(key=lambda r: r["finished"])
    return runs


def resolve_tz(name):
    if name and ZoneInfo is not None:
        try:
            return ZoneInfo(name)
        except Exception:  # noqa: BLE001 - unknown zone names raise several error types
            print(f"Unknown time zone {name!r}; using this machine's.", file=sys.stderr)
    return datetime.now().astimezone().tzinfo


# ----------------------------------------------------------------------------------- aggregation

def aggregate(runs):
    totals = dict.fromkeys(COUNTS, 0)
    distinct = set()
    with_scenarios = False
    for run in runs:
        for key in COUNTS:
            totals[key] += run[key]
        if run["scenarios"] is not None:
            with_scenarios = True
            distinct.update((s["id"], s["browser"]) for s in run["scenarios"])
    totals["runs"] = len(runs)
    totals["distinct"] = len(distinct) if with_scenarios else None
    totals["passRate"] = (totals["passed"] / totals["total"]) if totals["total"] else None
    return totals


def local_day(run, tz):
    return run["finished"].astimezone(tz).date()


def day_buckets(runs, tz, today, days):
    """[(day, [runs])] for the `days` calendar days ending today, oldest first, empty days included."""
    by_day = defaultdict(list)
    for run in runs:
        by_day[local_day(run, tz)].append(run)
    return [(d, by_day.get(d, [])) for d in (today - timedelta(days=n) for n in range(days - 1, -1, -1))]


def week_buckets(runs, tz, today, weeks=WEEKS):
    monday = today - timedelta(days=today.weekday())
    starts = [monday - timedelta(weeks=n) for n in range(weeks - 1, -1, -1)]
    by_week = defaultdict(list)
    for run in runs:
        day = local_day(run, tz)
        by_week[day - timedelta(days=day.weekday())].append(run)
    return [(start, by_week.get(start, [])) for start in starts]


def month_buckets(runs, tz, today, months=MONTHS):
    starts = []
    year, month = today.year, today.month
    for _ in range(months):
        starts.append(date(year, month, 1))
        year, month = (year, month - 1) if month > 1 else (year - 1, 12)
    starts.reverse()
    by_month = defaultdict(list)
    for run in runs:
        day = local_day(run, tz)
        by_month[date(day.year, day.month, 1)].append(run)
    return [(start, by_month.get(start, [])) for start in starts]


def from_first_run(buckets):
    """Drops the empty buckets before the first one with a run, so a young history has no blank rows."""
    for index, (_, runs) in enumerate(buckets):
        if runs:
            return buckets[index:]
    return buckets[-1:]


def problem_tests(runs):
    """Scenarios that failed or were flaky at least once: worst first."""
    stats = {}
    for run in runs:
        for s in run["scenarios"] or []:
            key = (s["id"], s["browser"])
            entry = stats.setdefault(key, {"id": s["id"], "name": s["name"], "browser": s["browser"],
                                           "runs": 0, "failed": 0, "flaky": 0, "lastStatus": "",
                                           "lastProblem": None, "trail": []})
            entry["runs"] += 1
            entry["name"] = s["name"]
            entry["lastStatus"] = s["status"]
            entry["trail"].append(s["status"])
            if s["status"] in ("failed", "flaky"):
                entry[s["status"]] += 1
                entry["lastProblem"] = run["finished"]
    flagged = [e for e in stats.values() if e["failed"] or e["flaky"]]
    flagged.sort(key=lambda e: (-e["failed"], -e["flaky"], e["name"]))
    return flagged


# ----------------------------------------------------------------------------------- record (CI)

def merge(summaries, label="", run_url=""):
    """Several shard/browser summaries of ONE run -> one run record (same shape as a summary)."""
    def joined(key):
        return ", ".join(sorted({str(s.get(key)) for s in summaries if s.get(key)})) or "?"

    browsers = {str(s.get("browser")) for s in summaries if s.get("browser")}
    merged = {key: sum(as_int(s.get(key)) for s in summaries) for key in COUNTS}
    times = [t for t in (parse_time(s.get("finishedAt")) for s in summaries) if t]
    merged.update(
        suite=joined("suite"), environment=joined("environment"), browser=joined("browser"),
        tagFilter=joined("tagFilter") if any(s.get("tagFilter") for s in summaries) else "",
        durationMs=max((as_int(s.get("durationMs")) for s in summaries), default=0),
        finishedAt=max(times).isoformat().replace("+00:00", "Z") if times else None,
        parts=len(summaries), label=label, runUrl=run_url,
        buildNumber=joined("buildNumber"), commit=joined("commit"),
    )
    scenarios = []
    for s in summaries:
        for item in s.get("scenarios") or []:
            if isinstance(item, dict):
                entry = {k: item.get(k) for k in ("id", "name", "status", "durationMs")}
                if len(browsers) > 1:
                    entry["browser"] = s.get("browser")
                scenarios.append(entry)
    merged["scenarios"] = scenarios if any("scenarios" in s for s in summaries) else None
    if merged["scenarios"] is None:
        del merged["scenarios"]
    return merged


def file_name(finished_at, suite):
    """Same rule as ExecutionHistory.fileName in the framework, so names sort by time."""
    stamp = re.sub(r"[^0-9TZ]", "", finished_at)
    slug = re.sub(r"[^a-z0-9]+", "-", suite.lower()).strip("-") or "run"
    return f"{stamp}-{slug}.json"


def prune(runs_dir, keep_days, today):
    cutoff = (today - timedelta(days=keep_days)).strftime("%Y%m%d")
    for path in Path(runs_dir).glob("*.json"):
        stamp = path.name[:8]
        if keep_days > 0 and stamp.isdigit() and stamp < cutoff:
            path.unlink()


def record(paths, history_dir, label="", run_url="", keep_days=KEEP_DAYS):
    summaries = []
    for raw in paths:
        path = Path(raw)
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            print(f"Skipped unreadable summary {path}: {error.__class__.__name__}", file=sys.stderr)
            continue
        if isinstance(data, dict):
            summaries.append(data)
    if not summaries:
        print("No execution summary to record.", file=sys.stderr)
        return None
    merged = merge(summaries, label, run_url)
    if not merged["finishedAt"]:
        print("The summaries have no finishedAt; nothing recorded.", file=sys.stderr)
        return None
    runs_dir = Path(history_dir) / "runs"
    runs_dir.mkdir(parents=True, exist_ok=True)
    target = runs_dir / file_name(merged["finishedAt"], merged["suite"])
    target.write_text(json.dumps(merged, indent=2), encoding="utf-8")
    prune(runs_dir, keep_days, datetime.now(timezone.utc).date())
    return target


# ----------------------------------------------------------------------------------- formatting

def pct(rate):
    return "–" if rate is None else f"{rate * 100:.1f}%"


def num(value):
    return "–" if value is None else f"{value:,}"


def dur(ms):
    seconds = ms / 1000
    if seconds < 60:
        return f"{seconds:.0f}s"
    minutes, seconds = divmod(int(seconds), 60)
    return f"{minutes}m {seconds:02d}s" if minutes < 60 else f"{minutes // 60}h {minutes % 60:02d}m"


def day_label(d):
    return f"{d.day} {d.strftime('%b')}"


def range_label(start, end):
    if start == end:
        return f"{day_label(start)} {start.year}"
    if start.year != end.year:
        return f"{day_label(start)} {start.year} – {day_label(end)} {end.year}"
    return f"{day_label(start)} – {day_label(end)} {end.year}"


def period_name(days):
    return "Today" if days == 1 else f"Last {days} days"


def week_name(start, today):
    end = start + timedelta(days=6)
    suffix = " (to date)" if start <= today <= end else ""
    return f"{day_label(start)} – {day_label(end)}{suffix}"


def month_name(start, today):
    suffix = " (to date)" if (start.year, start.month) == (today.year, today.month) else ""
    return f"{start.strftime('%b')} {start.year}{suffix}"


# ----------------------------------------------------------------------------------- Markdown

def markdown(runs, tz, today):
    lines = ["### Test execution history", ""]
    if not runs:
        return "\n".join(lines + ["No runs recorded yet.", ""])
    head = "| Period | Runs | Executions | Passed | Failed | Skipped | Flaky | Pass rate |"
    rule = "|---|---:|---:|---:|---:|---:|---:|---:|"

    def row(name, agg):
        return (f"| {name} | {agg['runs']} | {num(agg['total'])} | {num(agg['passed'])} | {num(agg['failed'])} "
                f"| {num(agg['skipped'])} | {num(agg['flaky'])} | {pct(agg['passRate'])} |")

    lines += [head, rule]
    for days in PERIODS:
        bucket = [r for _, rs in day_buckets(runs, tz, today, days) for r in rs]
        lines.append(row(period_name(days), aggregate(bucket)))
    lines += ["", "**Weekly**", "", head, rule]
    lines += [row(week_name(s, today), aggregate(rs))
              for s, rs in reversed(from_first_run(week_buckets(runs, tz, today))[-4:])]
    lines += ["", "**Monthly**", "", head, rule]
    lines += [row(month_name(s, today), aggregate(rs))
              for s, rs in reversed(from_first_run(month_buckets(runs, tz, today))[-3:])]
    lines += ["", f"Days in {tz}. The full report (artifact `execution-history`) has every day, week and month."]
    return "\n".join(lines) + "\n"


# ----------------------------------------------------------------------------------- HTML

def esc(value):
    return html.escape(str(value), quote=True)


def nice_max(value):
    """Axis top with little headroom whose quarter ticks are whole numbers (8, 12, 16, 20, 24, 32, 40, 48, 60, 64...)."""
    if value <= 4:
        return 4
    if value <= 8:
        return 8
    magnitude = 10 ** (len(str(int(value))) - 1)
    for step in (1, 1.2, 1.6, 2, 2.4, 3.2, 4, 4.8, 6, 6.4, 8, 10):
        top = round(step * magnitude)
        if top >= value:
            return top
    return 10 * magnitude


def bar_path(x, y, w, h, radius):
    """Bar with only the top corners rounded (the data end); flat on the baseline."""
    r = min(radius, w / 2, h)
    return (f"M{x:.1f},{y + h:.1f} V{y + r:.1f} Q{x:.1f},{y:.1f} {x + r:.1f},{y:.1f} "
            f"H{x + w - r:.1f} Q{x + w:.1f},{y:.1f} {x + w:.1f},{y + r:.1f} V{y + h:.1f} Z")


def chart(buckets, caption, label_every=1, show_totals=True):
    """Stacked bars (passed / failed / skipped) per bucket; buckets = [(label, tooltip, aggregate)]."""
    width, height = 760, 240
    left, right, top, bottom = 44, 8, 22, 34
    plot_w, plot_h = width - left - right, height - top - bottom
    peak = nice_max(max((a["total"] for _, _, a in buckets), default=0))
    slot = plot_w / max(len(buckets), 1)
    bar_w = max(4.0, min(36.0, slot * 0.62))
    gap = 2
    parts = [f'<svg class="chart" viewBox="0 0 {width} {height}" role="img" aria-label="{esc(caption)}">']
    for i in range(5):
        value = peak * i / 4
        y = top + plot_h - plot_h * i / 4
        parts.append(f'<line class="{"axis" if i == 0 else "grid"}" x1="{left}" x2="{width - right}" '
                     f'y1="{y:.1f}" y2="{y:.1f}"/>')
        parts.append(f'<text class="tick" x="{left - 6}" y="{y + 4:.1f}" text-anchor="end">'
                     f'{value:,.0f}</text>')
    for index, (label, tooltip, agg) in enumerate(buckets):
        cx = left + slot * index + slot / 2
        x = cx - bar_w / 2
        base = top + plot_h
        segments = [(agg["passed"], "passed"), (agg["failed"], "failed"), (agg["skipped"], "skipped")]
        segments = [(v, k) for v, k in segments if v > 0]
        y = base
        for position, (value, kind) in enumerate(segments):
            h = plot_h * value / peak
            y_top = y - h
            # A segment stacked on another leaves a 2px gap of surface beneath it.
            drawn = h if position == 0 else max(h - gap, 1.0)
            if position == len(segments) - 1:
                parts.append(f'<path class="seg {kind}" d="{bar_path(x, y_top, bar_w, drawn, 4)}"/>')
            else:
                parts.append(f'<rect class="seg {kind}" x="{x:.1f}" y="{y_top:.1f}" width="{bar_w:.1f}" '
                             f'height="{drawn:.1f}"/>')
            y -= h
        if show_totals and agg["total"]:
            parts.append(f'<text class="value" x="{cx:.1f}" y="{y - 5:.1f}" text-anchor="middle">'
                         f'{agg["total"]:,}</text>')
        if not agg["runs"]:
            parts.append(f'<line class="none" x1="{x:.1f}" x2="{x + bar_w:.1f}" y1="{base - 1:.1f}" '
                         f'y2="{base - 1:.1f}"/>')
        if index % label_every == 0 or index == len(buckets) - 1:
            parts.append(f'<text class="tick" x="{cx:.1f}" y="{height - bottom + 16}" text-anchor="middle">'
                         f'{esc(label)}</text>')
        tip = (f"{tooltip}: {agg['runs']} run(s), {agg['total']:,} executed, {agg['passed']:,} passed, "
               f"{agg['failed']:,} failed, {agg['skipped']:,} skipped, pass rate {pct(agg['passRate'])}")
        parts.append(f'<rect class="hit" x="{left + slot * index:.1f}" y="{top}" width="{slot:.1f}" '
                     f'height="{plot_h}"><title>{esc(tip)}</title></rect>')
    parts.append("</svg>")
    return "".join(parts)


LEGEND = ('<div class="legend"><span><i class="key passed"></i>Passed</span>'
          '<span><i class="key failed"></i>Failed</span><span><i class="key skipped"></i>Skipped</span>'
          '<span class="muted">Hover a bar for its numbers. A dash on the baseline means no runs.</span></div>')


def tiles(agg):
    def tile(label, value, extra=""):
        return f'<div class="tile{extra}"><div class="tile-label">{label}</div><div class="tile-value">{value}</div></div>'
    failed_cls = " bad" if agg["failed"] else ""
    return ('<div class="tiles">'
            + tile("Test executions", num(agg["total"]))
            + tile("Distinct test cases", num(agg["distinct"]))
            + tile("Passed", num(agg["passed"]), " good")
            + tile("Failed", num(agg["failed"]), failed_cls)
            + tile("Pass rate", pct(agg["passRate"]))
            + tile("Runs", num(agg["runs"]))
            + "</div>")


def table(rows, first_header):
    out = [f'<table><thead><tr><th scope="col">{esc(first_header)}</th><th>Runs</th><th>Executions</th>'
           '<th>Distinct tests</th><th>Passed</th><th>Failed</th><th>Skipped</th><th>Flaky</th>'
           '<th>Pass rate</th></tr></thead><tbody>']
    for name, agg in rows:
        failed = f'<span class="bad-text">{num(agg["failed"])}</span>' if agg["failed"] else num(agg["failed"])
        out.append(f'<tr><th scope="row">{esc(name)}</th><td>{agg["runs"]}</td><td>{num(agg["total"])}</td>'
                   f'<td>{num(agg["distinct"])}</td><td>{num(agg["passed"])}</td><td>{failed}</td>'
                   f'<td>{num(agg["skipped"])}</td><td>{num(agg["flaky"])}</td><td>{pct(agg["passRate"])}</td></tr>')
    out.append("</tbody></table>")
    return "".join(out)


TRAIL = {"passed": ("p", "passed"), "failed": ("f", "failed"), "flaky": ("F", "flaky"), "skipped": ("s", "skipped")}


def render_html(runs, tz, today, generated, title):
    tz_name = str(tz)
    sections = []

    # At a glance: one row per period.
    period_runs = {d: [r for _, rs in day_buckets(runs, tz, today, d) for r in rs] for d in PERIODS}
    glance_rows = []
    for days in PERIODS:
        start = today - timedelta(days=days - 1)
        glance_rows.append((f"{period_name(days)} ({range_label(start, today)})", aggregate(period_runs[days])))
    sections.append('<section><h2>At a glance</h2>'
                    '<p class="note">Every run in the period counts, whatever the suite. Calendar days, '
                    'today included.</p>' + table(glance_rows, "Period") + "</section>")

    # Period tabs: tiles + day-by-day chart and table.
    # Radio inputs sit directly before the tab bar and panels, so CSS sibling selectors can show
    # the chosen panel without JavaScript.
    inputs, tabs, panels = [], [], []
    for days in PERIODS:
        checked = " checked" if days == 7 else ""
        inputs.append(f'<input class="tab-input" type="radio" name="period" id="p{days}"{checked}>')
        tabs.append(f'<label for="p{days}">{esc(period_name(days))}</label>')
        buckets = day_buckets(runs, tz, today, days)
        agg = aggregate(period_runs[days])
        chart_buckets = [(day_label(d), d.strftime("%a %d %b %Y"), aggregate(rs)) for d, rs in buckets]
        body = tiles(agg)
        if days > 1:
            body += chart(chart_buckets, f"{period_name(days)}: test results per day",
                          label_every=1 if days <= 10 else 3, show_totals=days <= 10) + LEGEND
        body += table([(d.strftime("%a %d %b"), aggregate(rs)) for d, rs in reversed(buckets)], "Day")
        panels.append(f'<div class="panel" id="panel{days}">{body}</div>')
    sections.append('<section><h2>By period</h2>' + "".join(inputs) + '<div class="tabs">' + "".join(tabs)
                    + "</div>" + "".join(panels) + "</section>")

    weeks = from_first_run(week_buckets(runs, tz, today))
    sections.append('<section><h2>Weekly</h2><p class="note">Monday to Sunday, last 12 weeks.</p>'
                    + chart([(day_label(s), week_name(s, today), aggregate(rs)) for s, rs in weeks],
                            "Test results per week") + LEGEND
                    + table([(week_name(s, today), aggregate(rs)) for s, rs in reversed(weeks)], "Week")
                    + "</section>")

    months = from_first_run(month_buckets(runs, tz, today))
    sections.append('<section><h2>Monthly</h2><p class="note">Last 12 months.</p>'
                    + chart([(s.strftime("%b %y"), month_name(s, today), aggregate(rs)) for s, rs in months],
                            "Test results per month") + LEGEND
                    + table([(month_name(s, today), aggregate(rs)) for s, rs in reversed(months)], "Month")
                    + "</section>")

    recent = period_runs[30]
    problems = problem_tests(recent)
    if problems:
        rows = []
        for p in problems[:50]:
            trail = "".join(f'<i class="dot {TRAIL.get(s, ("?", "skipped"))[1]}" title="{esc(s)}">'
                            f'{TRAIL.get(s, ("?", ""))[0]}</i>' for s in p["trail"][-14:])
            last = p["lastProblem"].astimezone(tz).strftime("%d %b %H:%M") if p["lastProblem"] else "–"
            browser = f'<span class="muted"> · {esc(p["browser"])}</span>' if "," not in p["browser"] else ""
            rows.append(f'<tr><th scope="row">{esc(p["name"])}<div class="muted small">{esc(p["id"])}{browser}'
                        f'</div></th><td>{p["runs"]}</td><td>{p["failed"]}</td><td>{p["flaky"]}</td>'
                        f'<td>{esc(p["lastStatus"])}</td><td>{last}</td><td class="trail">{trail}</td></tr>')
        problem_html = ('<table><thead><tr><th scope="col">Test case</th><th>Runs</th><th>Failed</th><th>Flaky</th>'
                        '<th>Latest</th><th>Last problem</th><th>Last 14 results (oldest first)</th></tr></thead>'
                        f'<tbody>{"".join(rows)}</tbody></table>')
    elif any(r["scenarios"] is not None for r in recent):
        problem_html = '<p>No test case failed or needed a retry in the last 30 days.</p>'
    else:
        problem_html = '<p class="note">No per-test-case results were recorded in the last 30 days.</p>'
    sections.append('<section><h2>Tests needing attention (last 30 days)</h2>'
                    '<p class="note">Failed, or passed only on a retry (flaky), at least once. Key: '
                    '<i class="dot passed">p</i> passed <i class="dot failed">f</i> failed '
                    '<i class="dot flaky">F</i> flaky <i class="dot skipped">s</i> skipped.</p>'
                    + problem_html + "</section>")

    run_rows = []
    for run in reversed(recent):
        when = run["finished"].astimezone(tz).strftime("%a %d %b %H:%M")
        name = esc(run["label"] or run["suite"])
        if run["runUrl"].startswith("https://"):
            name = f'<a href="{esc(run["runUrl"])}">{name}</a>'
        tags = f'<div class="muted small">{esc(run["tagFilter"])}</div>' if run["tagFilter"] else ""
        rate = pct(run["passed"] / run["total"]) if run["total"] else "–"
        failed = f'<span class="bad-text">{run["failed"]}</span>' if run["failed"] else "0"
        run_rows.append(f'<tr><th scope="row">{when}</th><td>{name}{tags}</td><td>{esc(run["environment"])}</td>'
                        f'<td>{esc(run["browser"])}</td><td>{run["total"]}</td><td>{run["passed"]}</td>'
                        f'<td>{failed}</td><td>{run["skipped"]}</td><td>{run["flaky"]}</td><td>{rate}</td>'
                        f'<td>{dur(run["durationMs"])}</td></tr>')
    runs_html = ('<table><thead><tr><th scope="col">Finished</th><th>Run</th><th>Env</th><th>Browser</th>'
                 '<th>Executions</th><th>Passed</th><th>Failed</th><th>Skipped</th><th>Flaky</th><th>Pass rate</th>'
                 f'<th>Duration</th></tr></thead><tbody>{"".join(run_rows)}</tbody></table>'
                 if run_rows else '<p class="note">No runs in the last 30 days.</p>')
    sections.append(f'<section><h2>Runs (last 30 days)</h2>{runs_html}</section>')

    first = runs[0]["finished"].astimezone(tz).strftime("%d %b %Y") if runs else "–"
    meta = (f"Generated {generated.astimezone(tz).strftime('%d %b %Y %H:%M')} · times in {esc(tz_name)} · "
            f"{len(runs)} run(s) recorded since {first}")
    panel_css = ",".join(f"#p{d}:checked ~ #panel{d}" for d in PERIODS)
    tab_css = ",".join(f'#p{d}:checked ~ .tabs label[for="p{d}"]' for d in PERIODS)
    focus_css = ",".join(f'#p{d}:focus-visible ~ .tabs label[for="p{d}"]' for d in PERIODS)
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(title)}</title>
<style>
:root {{
  color-scheme: light;
  --page: #f9f9f7; --surface: #fcfcfb; --ink: #0b0b0b; --ink-2: #52514e; --muted: #898781;
  --grid: #e1e0d9; --axis: #c3c2b7; --border: rgba(11,11,11,0.10);
  --passed: #0ca30c; --failed: #d03b3b; --skipped: #b5b3ab; --flaky: #fab219; --bad-text: #b02a2a;
}}
@media (prefers-color-scheme: dark) {{
  :root {{
    color-scheme: dark;
    --page: #0d0d0d; --surface: #1a1a19; --ink: #f0efec; --ink-2: #c3c2b7; --muted: #898781;
    --grid: #2c2c2a; --axis: #383835; --border: rgba(255,255,255,0.10);
    --skipped: #5f5e59; --bad-text: #ee7d7d;
  }}
}}
* {{ box-sizing: border-box; }}
body {{ margin: 0; background: var(--page); color: var(--ink);
  font: 14px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }}
main {{ max-width: 1040px; margin: 0 auto; padding: 32px 20px 64px; }}
h1 {{ font-size: 24px; margin: 0 0 4px; }}
h2 {{ font-size: 17px; margin: 0 0 8px; }}
.meta, .note, .muted {{ color: var(--ink-2); }}
.small {{ font-size: 12px; font-weight: 400; }}
section {{ background: var(--surface); border: 1px solid var(--border); border-radius: 10px;
  padding: 20px; margin-top: 20px; overflow-x: auto; }}
table {{ width: 100%; border-collapse: collapse; margin-top: 12px; font-variant-numeric: tabular-nums; }}
th, td {{ padding: 6px 8px; border-bottom: 1px solid var(--grid); text-align: right; vertical-align: top; }}
thead th {{ color: var(--ink-2); font-weight: 600; font-size: 12px; border-bottom-color: var(--axis); }}
th:first-child, td:nth-child(2):not(:last-child) {{ text-align: left; }}
tbody th {{ font-weight: 500; }}
.bad-text {{ color: var(--bad-text); font-weight: 600; }}
.tabs {{ display: flex; flex-wrap: wrap; gap: 6px; margin: 4px 0 4px; }}
.tab-input {{ position: absolute; opacity: 0; pointer-events: none; }}
.tabs label {{ padding: 6px 12px; border: 1px solid var(--border); border-radius: 999px; cursor: pointer;
  color: var(--ink-2); }}
{tab_css} {{ background: var(--ink); color: var(--surface); border-color: var(--ink); }}
{focus_css} {{ outline: 2px solid var(--ink); outline-offset: 2px; }}
.panel {{ display: none; }}
{panel_css} {{ display: block; }}
.tiles {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px; margin: 14px 0 6px; }}
.tile {{ border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; }}
.tile-label {{ color: var(--ink-2); font-size: 12px; }}
.tile-value {{ font-size: 24px; font-weight: 600; font-variant-numeric: tabular-nums; }}
.tile.good .tile-value::before {{ content: "✓ "; color: var(--passed); font-size: 18px; }}
.tile.bad .tile-value {{ color: var(--bad-text); }}
.tile.bad .tile-value::before {{ content: "✕ "; font-size: 18px; }}
.chart {{ width: 100%; height: auto; margin-top: 10px; display: block; }}
.chart .grid {{ stroke: var(--grid); stroke-width: 1; }}
.chart .axis {{ stroke: var(--axis); stroke-width: 1; }}
.chart .tick {{ fill: var(--muted); font-size: 11px; }}
.chart .value {{ fill: var(--ink-2); font-size: 11px; }}
.chart .none {{ stroke: var(--axis); stroke-width: 2; }}
.chart .seg.passed {{ fill: var(--passed); }}
.chart .seg.failed {{ fill: var(--failed); }}
.chart .seg.skipped {{ fill: var(--skipped); }}
.chart .hit {{ fill: transparent; }}
.chart .hit:hover {{ fill: var(--ink); fill-opacity: 0.05; }}
.legend {{ display: flex; flex-wrap: wrap; gap: 14px; font-size: 12px; color: var(--ink-2); margin-top: 4px; }}
.key {{ display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 6px; vertical-align: -1px; }}
.key.passed {{ background: var(--passed); }} .key.failed {{ background: var(--failed); }}
.key.skipped {{ background: var(--skipped); }}
.dot {{ display: inline-block; width: 13px; height: 13px; margin: 0 1px; border-radius: 3px; font: 600 9px/13px
  system-ui, sans-serif; text-align: center; font-style: normal; color: #fff; }}
.dot.passed {{ background: var(--passed); }} .dot.failed {{ background: var(--failed); }}
.dot.flaky {{ background: var(--flaky); color: #0b0b0b; }} .dot.skipped {{ background: var(--skipped); color: #0b0b0b; }}
td.trail {{ text-align: left; white-space: nowrap; }}
a {{ color: inherit; }}
@media print {{ section {{ break-inside: avoid; }} .tabs {{ display: none; }} .panel {{ display: none !important; }}
  #panel30 {{ display: block !important; }} }}
</style>
</head>
<body>
<main>
<h1>{esc(title)}</h1>
<div class="meta">{meta}</div>
{"".join(sections)}
<p class="note small" style="margin-top:20px">Executions = passed + failed + skipped; a test case run three
times counts three times. Distinct test cases counts each one once (per browser). Pass rate = passed ÷
executions. Flaky = failed first and passed on a retry; it is also counted as passed.</p>
</main>
</body>
</html>
"""


# ----------------------------------------------------------------------------------- CLI

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)

    rep = sub.add_parser("report", help="write the HTML report")
    rep.add_argument("--history", default=os.environ.get("HISTORY_DIR", DEFAULT_HISTORY))
    rep.add_argument("--out", default=DEFAULT_OUT)
    rep.add_argument("--tz", default=os.environ.get("HISTORY_TZ"), help="e.g. Asia/Kolkata (default: this machine)")
    rep.add_argument("--title", default="Test execution history")

    tab = sub.add_parser("table", help="print a Markdown summary (for the CI job summary)")
    tab.add_argument("--history", default=os.environ.get("HISTORY_DIR", DEFAULT_HISTORY))
    tab.add_argument("--tz", default=os.environ.get("HISTORY_TZ"))

    rec = sub.add_parser("record", help="merge one run's summaries (shards/browsers) into the history")
    rec.add_argument("summaries", nargs="+")
    rec.add_argument("--history", default=os.environ.get("HISTORY_DIR", DEFAULT_HISTORY))
    rec.add_argument("--label", default="")
    rec.add_argument("--run-url", default="")
    rec.add_argument("--keep-days", type=int, default=KEEP_DAYS)

    args = parser.parse_args(argv)
    if args.command == "record":
        path = record(args.summaries, args.history, args.label, args.run_url, args.keep_days)
        if path:
            print(f"Recorded {path}")
        return 0

    tz = resolve_tz(args.tz)
    now = datetime.now(timezone.utc)
    today = now.astimezone(tz).date()
    runs = load_runs(args.history)
    if args.command == "table":
        sys.stdout.write(markdown(runs, tz, today))
        return 0
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(render_html(runs, tz, today, now, args.title), encoding="utf-8")
    print(f"Execution history report: {out} ({len(runs)} run(s) in {args.history})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
