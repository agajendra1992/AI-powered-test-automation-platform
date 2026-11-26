#!/usr/bin/env bash
# Builds the Allure HTML report from target/allure-results into target/allure-report, and the
# execution history report (last 1/3/7/10/30 days, weekly, monthly) into target/execution-history.
# Uses the Allure CLI version pinned in pom.xml (downloaded once into .allure/ by the Maven plugin).
#
# Allure's Trend charts need the previous report's history/ folder. It is kept in
# $HISTORY_DIR/allure-history (default .qa-history, outside target/ so mvn clean keeps it),
# copied into the results before each build and refreshed after it.
set -euo pipefail
cd "$(dirname "$0")/.."
history_dir="${HISTORY_DIR:-.qa-history}"
cli=$(find .allure -path "*/bin/allure" 2>/dev/null | head -1 || true)
if [[ -z "${cli}" ]]; then
  mvn -q -B allure:report >/dev/null   # downloads the pinned CLI into .allure/
  cli=$(find .allure -path "*/bin/allure" | head -1)
fi
# Always replace: results left by a run without `mvn clean` may hold an older copy.
if [[ -d "${history_dir}/allure-history" ]]; then
  mkdir -p target/allure-results
  rm -rf target/allure-results/history
  cp -R "${history_dir}/allure-history" target/allure-results/history
fi
"${cli}" generate target/allure-results -o target/allure-report --clean
if [[ -d target/allure-report/history ]]; then
  mkdir -p "${history_dir}"
  rm -rf "${history_dir}/allure-history"
  cp -R target/allure-report/history "${history_dir}/allure-history"
fi
echo "Open with: ${cli} open target/allure-report"
# Best effort: the Allure report above is already built, and a problem here must not fail it.
if command -v python3 >/dev/null 2>&1; then
  python3 scripts/execution_history.py report --history "${history_dir}" \
    || echo "Could not build the execution history report (scripts/execution_history.py)." >&2
else
  echo "python3 not found: skipped the execution history report (scripts/execution_history.py)." >&2
fi
