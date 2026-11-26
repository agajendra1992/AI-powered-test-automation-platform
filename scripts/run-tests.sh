#!/usr/bin/env bash
# Runs a test suite locally, then builds the Allure and execution history reports and opens both
# in the browser, whether the tests passed or failed. Exits with the test run's exit code.
#
#   scripts/run-tests.sh                                        # smoke (testng-smoke.xml)
#   scripts/run-tests.sh -DsuiteXmlFile=testng-regression.xml
#   scripts/run-tests.sh -DsuiteXmlFile=testng-regression.xml -Dbrowser=firefox -Dcucumber.filter.tags="@payment"
#
# Arguments go to `mvn test` unchanged. Set QA_NO_OPEN=1 to build the reports without opening them.
# The demo app must be running for env=local (see README, Quick start).
set -uo pipefail
cd "$(dirname "$0")/.."

# Fresh results for this run only (the Allure trend comes from the history folder, not old results).
rm -rf target/allure-results target/allure-report

mvn -B test "$@"
status=$?

if [[ -z "$(ls -A target/allure-results 2>/dev/null)" ]]; then
  echo "No test results were produced (exit code ${status}); see the Maven output above." >&2
  exit "${status}"
fi
scripts/allure-report.sh || echo "Building the reports failed; see above." >&2
scripts/open-reports.sh target || true
exit "${status}"
