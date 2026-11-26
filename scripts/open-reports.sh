#!/usr/bin/env bash
# Serves a folder of reports over http://127.0.0.1 and opens them in the browser.
# The Allure report must be served over HTTP (it does not work from file://), so one small local
# web server serves the whole folder; the execution history report is opened from the same server.
#
#   scripts/open-reports.sh                       # target/ (local run): allure-report + execution-history
#   scripts/open-reports.sh .allure/ci-report/run-123
#
# Expected layout inside the folder: allure-report/index.html and/or execution-history/index.html.
#
# Environment:
#   REPORT_PORT=4040        first port to try (the next free one is used if it is taken)
#   QA_BROWSER_APP="Google Chrome"   macOS only: open in this app instead of the default browser
#   QA_NO_OPEN=1            build and serve, but do not open a browser (also skipped when CI is set)
#
# The server binds to 127.0.0.1 only and runs in the background; stop it with: scripts/open-reports.sh --stop
set -euo pipefail
cd "$(dirname "$0")/.."
state_dir=".allure"
pid_file="${state_dir}/report-server.pid"
mkdir -p "${state_dir}"

stop_server() {
  if [[ -f "${pid_file}" ]]; then
    read -r pid _ < "${pid_file}" || true
    if [[ -n "${pid:-}" ]] && kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
    rm -f "${pid_file}"
  fi
}

if [[ "${1:-}" == "--stop" ]]; then
  stop_server
  echo "Report server stopped."
  exit 0
fi

root="${1:-target}"
if [[ ! -d "${root}" ]]; then
  echo "No report folder '${root}'." >&2
  exit 1
fi
root=$(cd "${root}" && pwd)

pages=()
[[ -f "${root}/allure-report/index.html" ]] && pages+=("allure-report/index.html")
[[ -f "${root}/execution-history/index.html" ]] && pages+=("execution-history/index.html")
if (( ${#pages[@]} == 0 )); then
  echo "No reports in ${root} (expected allure-report/ or execution-history/). Build them first: scripts/allure-report.sh" >&2
  exit 1
fi

# One server at a time: a new folder replaces the previous server.
stop_server
port=$(python3 - "${REPORT_PORT:-4040}" <<'PY'
import socket, sys
start = int(sys.argv[1])
for port in range(start, start + 50):
    with socket.socket() as s:
        try:
            s.bind(("127.0.0.1", port))
        except OSError:
            continue
        print(port)
        break
else:
    sys.exit("no free port")
PY
)
nohup python3 -m http.server "${port}" --bind 127.0.0.1 --directory "${root}" > "${state_dir}/report-server.log" 2>&1 &
echo "$! ${port} ${root}" > "${pid_file}"

# Wait until the server answers (condition-based, at most ~5 s).
for _ in $(seq 1 50); do
  if curl -fsS -o /dev/null "http://127.0.0.1:${port}/${pages[0]}" 2>/dev/null; then break; fi
  sleep 0.1
done

urls=()
for page in "${pages[@]}"; do
  urls+=("http://127.0.0.1:${port}/${page}")
done
echo "Reports (served from ${root}):"
printf '  %s\n' "${urls[@]}"

if [[ -n "${CI:-}" || -n "${QA_NO_OPEN:-}" ]]; then
  exit 0
fi
for url in "${urls[@]}"; do
  if [[ "$(uname)" == "Darwin" ]]; then
    if [[ -n "${QA_BROWSER_APP:-}" ]]; then open -a "${QA_BROWSER_APP}" "${url}"; else open "${url}"; fi
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "${url}" >/dev/null 2>&1 &
  else
    echo "Open ${url} in your browser."
  fi
done
echo "Stop the report server with: scripts/open-reports.sh --stop"
