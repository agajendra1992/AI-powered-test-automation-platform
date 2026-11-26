"""Tests for scripts/execution_history.py (no network, temp folders only).

    python3 -m unittest discover -s scripts/tests -v
"""
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import execution_history as eh  # noqa: E402

try:
    from zoneinfo import ZoneInfo
    KOLKATA = ZoneInfo("Asia/Kolkata")
except Exception:  # noqa: BLE001 - no tz database on this machine
    KOLKATA = None


def summary(finished, passed=0, failed=0, skipped=0, flaky=0, suite="Regression", browser="chromium",
            scenarios=None, **extra):
    data = {"suite": suite, "environment": "local", "browser": browser, "total": passed + failed + skipped,
            "passed": passed, "failed": failed, "skipped": skipped, "flaky": flaky, "durationMs": 60_000,
            "finishedAt": finished}
    if scenarios is not None:
        data["scenarios"] = [{"id": i, "name": n, "status": s, "durationMs": 5} for i, n, s in scenarios]
    data.update(extra)
    return data


def write_run(folder, data):
    runs = Path(folder) / "runs"
    runs.mkdir(parents=True, exist_ok=True)
    path = runs / eh.file_name(data["finishedAt"], data["suite"])
    path.write_text(json.dumps(data), encoding="utf-8")
    return path


class MergeAndRecordTest(unittest.TestCase):

    def test_shards_of_one_run_become_one_run(self):
        merged = eh.merge([
            summary("2026-11-25T02:00:00Z", passed=10, failed=1, scenarios=[("a:1", "A", "passed")]),
            summary("2026-11-25T02:05:00Z", passed=8, skipped=2, durationMs=90_000,
                    scenarios=[("b:1", "B", "failed")]),
        ], label="Regression #41", run_url="https://github.com/o/r/actions/runs/1")

        self.assertEqual((merged["total"], merged["passed"], merged["failed"], merged["skipped"]), (21, 18, 1, 2))
        self.assertEqual(merged["finishedAt"], "2026-11-25T02:05:00Z")
        self.assertEqual(merged["durationMs"], 90_000)  # shards run side by side: the longest one
        self.assertEqual(merged["parts"], 2)
        self.assertEqual([s["id"] for s in merged["scenarios"]], ["a:1", "b:1"])
        self.assertNotIn("browser", merged["scenarios"][0])

    def test_cross_browser_scenarios_keep_their_browser(self):
        merged = eh.merge([
            summary("2026-11-25T02:00:00Z", passed=1, browser="chromium", scenarios=[("a:1", "A", "passed")]),
            summary("2026-11-25T02:00:00Z", failed=1, browser="firefox", scenarios=[("a:1", "A", "failed")]),
        ])
        self.assertEqual(merged["browser"], "chromium, firefox")
        self.assertEqual([s["browser"] for s in merged["scenarios"]], ["chromium", "firefox"])

    def test_recording_the_same_run_twice_keeps_one_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            shard = Path(tmp) / "execution-summary.json"
            shard.write_text(json.dumps(summary(datetime.now(timezone.utc).isoformat(), passed=3)), encoding="utf-8")
            first = eh.record([str(shard)], Path(tmp) / "history", label="Regression #41")
            second = eh.record([str(shard)], Path(tmp) / "history", label="Regression #41")
            self.assertEqual(first, second)
            self.assertEqual(len(list((Path(tmp) / "history" / "runs").glob("*.json"))), 1)
            self.assertEqual(json.loads(first.read_text())["label"], "Regression #41")

    def test_record_without_readable_summaries_writes_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(eh.record([str(Path(tmp) / "missing.json")], Path(tmp) / "history"))
            self.assertFalse((Path(tmp) / "history").exists())

    def test_prune_removes_runs_older_than_the_retention(self):
        with tempfile.TemporaryDirectory() as tmp:
            for name in ("20251001T000000Z-a.json", "20251026T000000Z-a.json", "20261125T000000Z-a.json", "x.json"):
                (Path(tmp) / name).write_text("{}")
            eh.prune(tmp, 30, date(2025, 11, 25))
            self.assertEqual(sorted(p.name for p in Path(tmp).iterdir()),
                             ["20251026T000000Z-a.json", "20261125T000000Z-a.json", "x.json"])

    def test_prune_command_removes_old_runs_from_the_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            runs = Path(tmp) / "runs"
            runs.mkdir()
            today = datetime.now(timezone.utc)
            old = runs / eh.file_name((today - timedelta(days=500)).strftime("%Y-%m-%dT%H:%M:%SZ"), "Regression")
            new = runs / eh.file_name(today.strftime("%Y-%m-%dT%H:%M:%SZ"), "Regression")
            old.write_text("{}")
            new.write_text("{}")
            self.assertEqual(eh.main(["prune", "--history", tmp, "--keep-days", "400"]), 0)
            self.assertEqual([p.name for p in runs.iterdir()], [new.name])

    def test_file_names_match_the_framework(self):
        # Same rule as ExecutionHistory.fileName (ExecutionHistoryTest checks the Java side).
        self.assertEqual(eh.file_name("2026-11-25T02:04:47.408Z", "Framework Regression"),
                         "20261125T020447408Z-framework-regression.json")


class AggregationTest(unittest.TestCase):

    def test_counts_executions_distinct_tests_and_pass_rate(self):
        runs = [eh.normalise(summary("2026-11-25T02:00:00Z", passed=3, failed=1,
                                     scenarios=[("a:1", "A", "passed"), ("b:1", "B", "failed")])),
                eh.normalise(summary("2026-11-25T05:00:00Z", passed=2,
                                     scenarios=[("a:1", "A", "passed"), ("c:1", "C", "flaky")]))]
        agg = eh.aggregate(runs)
        self.assertEqual((agg["runs"], agg["total"], agg["passed"], agg["failed"]), (2, 6, 5, 1))
        self.assertEqual(agg["distinct"], 3)
        self.assertAlmostEqual(agg["passRate"], 5 / 6)

    def test_an_empty_period_has_no_pass_rate(self):
        agg = eh.aggregate([])
        self.assertEqual((agg["runs"], agg["total"], agg["passRate"], agg["distinct"]), (0, 0, None, None))

    def test_day_week_and_month_buckets_include_empty_ones(self):
        runs = [eh.normalise(summary("2026-11-25T03:00:00Z", passed=1)),
                eh.normalise(summary("2026-11-20T03:00:00Z", passed=2)),
                eh.normalise(summary("2026-10-02T03:00:00Z", failed=1))]
        today = date(2026, 11, 25)

        days = eh.day_buckets(runs, timezone.utc, today, 7)
        self.assertEqual([d for d, _ in days][0], date(2026, 11, 19))
        self.assertEqual([len(rs) for _, rs in days], [0, 1, 0, 0, 0, 0, 1])

        weeks = eh.week_buckets(runs, timezone.utc, today, weeks=2)
        self.assertEqual([s for s, _ in weeks], [date(2026, 11, 16), date(2026, 11, 23)])
        self.assertEqual([len(rs) for _, rs in weeks], [1, 1])

        months = eh.month_buckets(runs, timezone.utc, today, months=3)
        self.assertEqual([s for s, _ in months], [date(2026, 9, 1), date(2026, 10, 1), date(2026, 11, 1)])
        self.assertEqual([len(rs) for _, rs in months], [0, 1, 2])

    @unittest.skipIf(KOLKATA is None, "no time zone database")
    def test_days_follow_the_report_time_zone(self):
        run = eh.normalise(summary("2026-11-24T20:00:00Z", passed=1))  # 01:30 on the 25th in India
        self.assertEqual(eh.local_day(run, KOLKATA), date(2026, 11, 25))
        self.assertEqual(eh.local_day(run, timezone.utc), date(2026, 11, 24))

    def test_month_buckets_cross_the_year(self):
        months = eh.month_buckets([], timezone.utc, date(2026, 2, 10), months=3)
        self.assertEqual([s for s, _ in months], [date(2025, 12, 1), date(2026, 1, 1), date(2026, 2, 1)])

    def test_weeks_and_months_start_at_the_first_run(self):
        runs = [eh.normalise(summary("2026-11-20T03:00:00Z", passed=2))]
        months = eh.from_first_run(eh.month_buckets(runs, timezone.utc, date(2026, 11, 25)))
        self.assertEqual([s for s, _ in months], [date(2026, 11, 1)])
        self.assertEqual(len(eh.from_first_run(eh.month_buckets([], timezone.utc, date(2026, 11, 25)))), 1)

    def test_axis_tops_leave_little_headroom_and_whole_ticks(self):
        for value, top in ((0, 4), (3, 4), (7, 8), (62, 64), (200, 200), (675, 800), (1001, 1200)):
            self.assertEqual(eh.nice_max(value), top)
            self.assertEqual(eh.nice_max(value) % 4, 0)

    def test_problem_tests_are_worst_first(self):
        runs = [eh.normalise(summary(f"2026-11-2{i}T02:00:00Z", scenarios=[
            ("a:1", "Always passes", "passed"), ("b:1", "Sometimes flaky", "flaky" if i == 1 else "passed"),
            ("c:1", "Often fails", "failed" if i < 3 else "passed")])) for i in range(1, 4)]
        problems = eh.problem_tests(runs)
        self.assertEqual([p["name"] for p in problems], ["Often fails", "Sometimes flaky"])
        self.assertEqual((problems[0]["failed"], problems[0]["runs"], problems[0]["lastStatus"]), (2, 3, "passed"))
        self.assertEqual(problems[0]["trail"], ["failed", "failed", "passed"])


class TimeZoneTest(unittest.TestCase):

    @unittest.skipIf(KOLKATA is None, "no time zone database")
    def test_a_named_zone_is_used_as_is(self):
        self.assertEqual(str(eh.resolve_tz("Asia/Kolkata")), "Asia/Kolkata")

    @unittest.skipIf(KOLKATA is None, "no time zone database")
    def test_without_a_name_the_machine_zone_keeps_daylight_saving(self):
        with mock.patch.dict(os.environ, {"TZ": "Europe/London"}):
            tz = eh.resolve_tz(None)
        self.assertEqual(str(tz), "Europe/London")
        july = eh.normalise(summary("2026-07-09T23:30:00Z", passed=1))  # 00:30 BST on 10 July
        self.assertEqual(eh.local_day(july, tz), date(2026, 7, 10))

    @unittest.skipIf(KOLKATA is None, "no time zone database")
    def test_an_unknown_name_falls_back_to_the_machine_zone(self):
        with mock.patch.dict(os.environ, {"TZ": "Asia/Kolkata"}), redirect_stderr(io.StringIO()) as err:
            tz = eh.resolve_tz("Not/AZone")
        self.assertEqual(str(tz), "Asia/Kolkata")
        self.assertIn("Unknown time zone", err.getvalue())


class OutputTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        now = datetime.now(timezone.utc).replace(microsecond=0)
        write_run(self.tmp.name, summary(now.isoformat().replace("+00:00", "Z"), passed=9, failed=1,
                                         scenarios=[("f.feature:3", "<script>alert(1)</script>", "failed")]))
        (Path(self.tmp.name) / "runs" / "broken.json").write_text("{not json")

    def test_unreadable_runs_are_skipped(self):
        self.assertEqual(len(eh.load_runs(self.tmp.name)), 1)

    def test_markdown_lists_every_period_week_and_month(self):
        text = eh.markdown(eh.load_runs(self.tmp.name), timezone.utc, datetime.now(timezone.utc).date())
        for period in ("| Today |", "| Last 3 days |", "| Last 7 days |", "| Last 10 days |", "| Last 30 days |"):
            self.assertIn(period, text)
        self.assertIn("| Today | 1 | 10 | 9 | 1 | 0 | 0 | 90.0% |", text)
        self.assertIn("**Weekly**", text)
        self.assertIn("(to date)", text)

    def test_html_report_has_every_view_and_escapes_names(self):
        out = Path(self.tmp.name) / "report" / "index.html"
        eh.main(["report", "--history", self.tmp.name, "--out", str(out), "--tz", "UTC"])
        page = out.read_text(encoding="utf-8")
        for heading in ("At a glance", "By period", "Weekly", "Monthly", "Tests needing attention", "Runs (last 30 days)"):
            self.assertIn(f"<h2>{heading}", page)
        for days in eh.PERIODS:
            self.assertIn(f'id="panel{days}"', page)
        self.assertNotIn("<script>alert(1)</script>", page)
        self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt;", page)

    def test_only_the_run_name_column_is_left_aligned(self):
        page = eh.render_html(eh.load_runs(self.tmp.name), timezone.utc, datetime.now(timezone.utc).date(),
                              datetime.now(timezone.utc), "History")
        self.assertIn("td.name {", page)
        self.assertNotIn("nth-child(2)", page)
        self.assertEqual(page.count('<td class="name">'), 1)

    def test_only_https_run_links_are_rendered(self):
        write_run(self.tmp.name, summary("2026-11-01T00:00:00Z", passed=1, suite="Smoke",
                                         runUrl="javascript:alert(1)", label="Smoke #2"))
        page = eh.render_html(eh.load_runs(self.tmp.name), timezone.utc, date(2026, 11, 20),
                              datetime.now(timezone.utc), "History")
        self.assertNotIn("javascript:alert", page)


if __name__ == "__main__":
    unittest.main()
