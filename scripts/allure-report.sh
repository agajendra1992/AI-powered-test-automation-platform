#!/usr/bin/env bash
# Builds the Allure HTML report from target/allure-results into target/allure-report.
# Uses the Allure CLI version pinned in pom.xml (downloaded once into .allure/ by the Maven plugin).
set -euo pipefail
cd "$(dirname "$0")/.."
cli=$(find .allure -path "*/bin/allure" 2>/dev/null | head -1 || true)
if [[ -z "${cli}" ]]; then
  mvn -q -B allure:report >/dev/null   # downloads the pinned CLI into .allure/
  cli=$(find .allure -path "*/bin/allure" | head -1)
fi
"${cli}" generate target/allure-results -o target/allure-report --clean
echo "Open with: ${cli} open target/allure-report"
