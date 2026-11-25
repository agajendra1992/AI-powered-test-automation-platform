# Execution history report

One page that answers, for management: **how many test cases ran, how many passed and how many
failed**, today and over the last 3, 7, 10 and 30 days, week by week and month by month. It counts
every run, whatever the suite and however many runs a period had.

```bash
scripts/allure-report.sh          # builds the Allure report AND target/execution-history/index.html
```

```bash
python3 scripts/execution_history.py report --tz Asia/Kolkata   # only the history report
```

The report is a single HTML file with no external files or scripts: open it, attach it to an
e-mail, or print it (printing shows the 30-day view).

## What it shows

| Section | Content |
|---|---|
| At a glance | One row per period: today, last 3, 7, 10 and 30 days (calendar days, today included) |
| By period | Tabs for the same five periods: totals, a chart per day, and a table per day |
| Weekly | Last 12 weeks, Monday to Sunday; the current week is marked *to date* |
| Monthly | Last 12 months; the current month is marked *to date* |
| Tests needing attention | Test cases that failed, or passed only on a retry, at least once in 30 days, worst first, with their recent results |
| Runs | Every run in the last 30 days: suite or CI label (linked to the Actions run), environment, browser, counts, duration |

Every row has: runs, **executions** (passed + failed + skipped; a test case run three times counts
three times), **distinct test cases** (each counted once, per browser), passed, failed, skipped,
flaky, and **pass rate** (passed ÷ executions). Flaky means failed first and passed on a retry; it is
also counted as passed, as in the execution summary.

## Where the data comes from

Each suite run that uses the framework's listener (smoke, regression, UI, API, e2e; not the
framework unit tests) writes `target/execution-summary.json`. Since this report, the summary also
lists every scenario's final result (`scenarios`: id, name, status, duration), and a copy is kept
in the **history folder**, outside `target/`, so `mvn clean` does not erase it:

```
.qa-history/
  runs/20261125T020447408Z-regression.json   one file per run, named by its UTC finish time
  allure-history/                             Allure's trend data, carried between report builds
```

| Setting | Default | Meaning |
|---|---|---|
| `history.enabled` | `true` | Keep a copy of every run |
| `history.dir` | `.qa-history` | History folder (git-ignored); `scripts/` read `HISTORY_DIR` |
| `history.keep.days` | `400` | Older runs are removed when a new run is recorded (12 monthly totals need ~13 months) |

Set them like any other setting: `-Dhistory.enabled=false`, `HISTORY_DIR=/shared/qa-history`, or in
`config/<env>.properties`. The report's days, weeks and months use this machine's time zone unless
`--tz` (or `HISTORY_TZ`) names one.

A run in Docker (`docker compose run --rm tests …`) writes the history inside the container. Mount
a folder on `/work/.qa-history` (or set `HISTORY_DIR` to a mounted path) to keep it.

## In GitHub Actions

The report job of `regression.yml` and `cross-browser.yml` (in `report.yml`):

1. restores the workflow's history from the Actions cache,
2. merges this run's shard/browser summaries into **one** run and adds it
   (`execution_history.py record`, labelled e.g. *Regression #41*, linked to the run),
3. builds the Allure report (with its Trend charts) and the history report,
4. writes the period, weekly and monthly tables to the run's summary page,
5. uploads the artifact **`execution-history`** (the report plus the raw runs, kept 90 days), and
6. saves the history to the cache for the next run.

Regression and cross-browser runs keep separate histories. Days follow `Asia/Kolkata`, like the
schedules; set the repository variable `HISTORY_TZ` to change it.

**Limit:** GitHub removes a cache that has not been used for 7 days, so a workflow that does not
run for a week starts a new history. The nightly regression runs every weekday, so it keeps its
history. To restart a history from an `execution-history` artifact, unpack its `runs/` into
`.qa-history/runs/` locally and run the report, or keep `.qa-history` on durable storage.

## Commands

| Command | Does |
|---|---|
| `execution_history.py report [--history DIR] [--out FILE] [--tz ZONE] [--title TEXT]` | Writes the HTML report (default `target/execution-history/index.html`) |
| `execution_history.py table [--history DIR] [--tz ZONE]` | Prints the periods, last 4 weeks and last 3 months as Markdown |
| `execution_history.py record SUMMARY... [--label TEXT] [--run-url URL] [--keep-days N]` | Merges one run's summaries into the history (CI); recording the same run again replaces it |

Tests: `python3 -m unittest discover -s scripts/tests -v` and `ExecutionHistoryTest` in the unit suite.
