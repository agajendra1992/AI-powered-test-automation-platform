#!/usr/bin/env bash
# Downloads the reports of a GitHub Actions run and opens them in the browser:
# the Allure report and the execution history report (last 1/3/7/10/30 days, weekly, monthly).
#
#   scripts/open-ci-report.sh                     # latest finished run of regression.yml
#   scripts/open-ci-report.sh cross-browser.yml   # latest finished run of that workflow
#   scripts/open-ci-report.sh 45309253449         # a specific run id
#
# Runs with a report job (regression, cross-browser) publish both reports as artifacts. For runs
# without one (ci.yml smoke) the Allure report is built here from the uploaded results, and the
# history report shows that run on its own.
# Needs the GitHub CLI (gh) signed in to this repository. Files go to .allure/ci-report/run-<id>/.
set -euo pipefail
cd "$(dirname "$0")/.."
target="${1:-regression.yml}"

if [[ "${target}" =~ ^[0-9]+$ ]]; then
  run_id="${target}"
else
  run_id=$(gh run list --workflow "${target}" --status completed --limit 1 --json databaseId --jq '.[0].databaseId // empty')
  [[ -n "${run_id}" ]] || { echo "No finished run of ${target} found." >&2; exit 1; }
fi
info=$(gh run view "${run_id}" --json workflowName,number,conclusion,headBranch,url \
  --jq '"\(.workflowName) #\(.number) on \(.headBranch): \(.conclusion // "running")\t\(.url)\t\(.workflowName) #\(.number)"')
IFS=$'\t' read -r title run_url label <<< "${info}"
echo "Run ${run_id}: ${title}"

dest=".allure/ci-report/run-${run_id}"
rm -rf "${dest}"
mkdir -p "${dest}"

# ---- Allure report ------------------------------------------------------------------------
if gh run download "${run_id}" -n allure-report -D "${dest}/allure-report" >/dev/null 2>&1; then
  echo "Allure report: downloaded"
elif gh run download "${run_id}" -p 'allure-results-*' -D "${dest}/results" >/dev/null 2>&1; then
  mkdir -p "${dest}/allure-results"
  for dir in "${dest}"/results/*/; do cp -R "${dir}". "${dest}/allure-results/"; done
  cli=$(find .allure -path "*/bin/allure" 2>/dev/null | head -1 || true)
  if [[ -z "${cli}" ]]; then
    mvn -q -B allure:report >/dev/null 2>&1 || true   # downloads the pinned CLI into .allure/
    cli=$(find .allure -path "*/bin/allure" | head -1)
  fi
  "${cli}" generate "${dest}/allure-results" -o "${dest}/allure-report" --clean >/dev/null
  echo "Allure report: built from the run's results"
else
  echo "Allure report: this run has no Allure results (it may have failed before tests ran)." >&2
fi

# ---- Execution history report -------------------------------------------------------------
# The artifact holds target/execution-history/index.html (and the history runs) under its own paths.
if gh run download "${run_id}" -n execution-history -D "${dest}/history-artifact" >/dev/null 2>&1; then
  page=$(find "${dest}/history-artifact" -path "*execution-history/index.html" | head -1)
  if [[ -n "${page}" ]]; then
    mkdir -p "${dest}/execution-history"
    cp "${page}" "${dest}/execution-history/index.html"
    echo "Execution history report: downloaded (all runs of this workflow kept in its history)"
  fi
fi
if [[ ! -f "${dest}/execution-history/index.html" ]] \
   && gh run download "${run_id}" -p 'summary-*' -D "${dest}/summaries" >/dev/null 2>&1; then
  shopt -s nullglob
  summaries=("${dest}"/summaries/*/execution-summary.json)
  if (( ${#summaries[@]} )); then
    python3 scripts/execution_history.py record "${summaries[@]}" --history "${dest}/history" \
      --label "${label}" --run-url "${run_url}" >/dev/null
    python3 scripts/execution_history.py report --history "${dest}/history" \
      --out "${dest}/execution-history/index.html" --title "Test execution history: ${label}" >/dev/null
    echo "Execution history report: built from this run only (its workflow keeps no history)"
  fi
fi

scripts/open-reports.sh "${dest}"
