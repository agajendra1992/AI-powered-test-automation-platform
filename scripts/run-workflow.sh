#!/usr/bin/env bash
# Starts a GitHub Actions workflow, follows it until it finishes, then downloads its reports
# (Allure + execution history) and opens them in the browser. Exits with the run's result.
#
#   scripts/run-workflow.sh regression.yml
#   scripts/run-workflow.sh regression.yml -f browser=firefox -f shards=2 -f tags=@payment
#   scripts/run-workflow.sh cross-browser.yml
#   scripts/run-workflow.sh ci.yml --ref my-branch
#
# Options after the workflow: -f key=value (workflow inputs) and --ref <branch> (default: current branch).
# Needs the GitHub CLI (gh) signed in; the workflow must have a workflow_dispatch trigger.
set -uo pipefail
cd "$(dirname "$0")/.."
workflow="${1:?usage: scripts/run-workflow.sh <workflow.yml> [-f key=value ...] [--ref branch]}"
shift
ref=$(git rev-parse --abbrev-ref HEAD)
fields=()
while (( $# )); do
  case "$1" in
    -f) fields+=(-f "$2"); shift 2 ;;
    --ref) ref="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
gh workflow run "${workflow}" --ref "${ref}" ${fields[@]+"${fields[@]}"} || exit 1

# The new run takes a few seconds to appear; match it by workflow, branch, trigger and start time.
run_id=""
for _ in $(seq 1 30); do
  run_id=$(gh run list --workflow "${workflow}" --branch "${ref}" --event workflow_dispatch --limit 5 \
    --json databaseId,createdAt --jq "[.[] | select(.createdAt >= \"${started}\")] | last | .databaseId // empty")
  [[ -n "${run_id}" ]] && break
  sleep 2
done
[[ -n "${run_id}" ]] || { echo "Started ${workflow}, but could not find the run; open it in GitHub Actions." >&2; exit 1; }
echo "Following run ${run_id} ($(gh run view "${run_id}" --json url --jq .url))"

gh run watch "${run_id}" --interval 30 --exit-status >/dev/null
status=$?
echo "Run ${run_id} finished: $(gh run view "${run_id}" --json conclusion --jq .conclusion)"
scripts/open-ci-report.sh "${run_id}" || true
exit "${status}"
