# Security validation

This is what was checked, how, and the result (2026-11-20). Re-run the automated checks with CI
(`ci.yml` → `security`, `build` → SpotBugs) and the commands below.

## Automated scans

| Scan | Tool | Result |
|---|---|---|
| Dependency vulnerabilities (Maven, pip) | Trivy 0.76.2 `fs`, HIGH/CRITICAL | Framework 0; demo app 0. **MCP server: 8 HIGH in Jackson 2.19.4** (pulled in by the Anthropic SDK), **fixed** by pinning Jackson 2.23.0, then 0. **RAG: chromadb 1.5.9, 2 CRITICAL + 2 HIGH, no fix released**: accepted with mitigations below |
| Committed secrets | Trivy secret scanner | 0 (the git-ignored `secrets/` folder of local dev tokens is excluded) |
| Dockerfile / Compose misconfiguration | Trivy misconfig, HIGH/CRITICAL | 0 (all images run as non-root; no secrets in images) |
| Static bug analysis | SpotBugs 4.10.5 (effort max, threshold medium) | Framework: 3 found, 3 fixed (2 possible null dereferences; the shared JSON mapper was exposed and is now hidden behind narrow methods). MCP: 26 found, 26 fixed (19 null-path cases, a negative `Math.abs(hashCode)`, a `SecureRandom` per call, an unclosed stream, an ignored delete result, a missing `serialVersionUID`). Only `EI_EXPOSE_REP*` is excluded, with a reason |
| Forbidden code | Checkstyle | JUnit, Selenium, Rest Assured, sleeps, empty catches and `System.out` are rejected in every module |
| Workflow security | actionlint + shellcheck | 0 findings; script injection, mistyped inputs and quoting are checked (probe-verified) |

### Accepted risk: chromadb CVE-2026-45829/45833/45830/45831

These concern ChromaDB's server API: code execution through the collection endpoints
(`trust_remote_code`), and cross-tenant authorisation. No fixed version exists.
**Mitigations in this platform:**
* The `chroma` container publishes no port. Only the `rag` service reaches it, on the internal
  Compose network.
* In local mode the RAG service uses embedded ChromaDB, so there is no server API at all.
* The RAG HTTP API never passes collection, model or tenant parameters from clients to ChromaDB.
* CI's Trivy job (`--ignore-unfixed`) starts failing as soon as a fixed release exists, and
  Dependabot opens the upgrade PR.

## Behavioural security checks (run against real services)

| Control | Check | Result |
|---|---|---|
| Authentication | MCP HTTP without a token; RAG without a key | 401 with `WWW-Authenticate: Bearer`; counted in `qa_mcp_auth_failures_total` and the audit log |
| Read-only by default | Viewer token calls `run_smoke_tests` (Docker platform) | `forbidden` |
| Tool-level authorisation | Runner calls `approve_execution` | `forbidden` (admin only) |
| Second-person approval | Staging run → AWAITING_APPROVAL; requester as admin approves own run (unit test); a different admin decides | Self-approval refused; the different admin's decision recorded and audited |
| Environment restriction | `environment=prod`; unknown environment in config | Rejected; configuration refused at start-up |
| Input validation / injection | `@api; rm -rf /`, `@api -DskipTests`, `../pom.xml`, `../../etc/passwd`, `--output=/tmp/x` / `HEAD;rm` as Git refs, unknown arguments | All rejected before anything runs |
| No shell | Only `mvn`/`mvnw` (and fixed `git diff`) are started, with argv lists | Verified by code review and tests; `maven.command=/bin/sh` refused |
| SSRF | Browser tool given `http://169.254.169.254/...`, `https://example.org/` | `forbidden`; requests to other origins are aborted inside the page |
| Secret and PII redaction | Scans of Allure, Cucumber, logs, failure records, MCP state, RAG index after runs with real passwords (including deliberately wrong ones) | No passwords, client secret, tokens or test card numbers found; RAG stores redacted text only |
| RAG access levels | Restricted bug report searched with an internal key | Not visible; visible to an admin key |
| Prompt injection | Instruction-like text in documents and logs | Flagged and down-ranked; always fenced as `<data>` for models; fences can't be closed from inside |
| AI cannot change results | Summaries contradicting failures; AI plans with invalid tags; AI evidence not in the record | Discarded or flagged; status always from the framework |
| Generated code | Acceptance by non-admins; overwriting existing files | Refused; files created only when absent |

## Re-run locally

```bash
docker run --rm -v "$PWD:/repo:ro" -v "$HOME/.m2:/root/.m2:ro" aquasec/trivy:0.76.2 fs /repo --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --skip-dirs /repo/target,/repo/mcp-server/target,/repo/rag/.venv,/repo/sut/.venv,/repo/secrets
```

```bash
mvn -B compile spotbugs:check && mvn -B -f mcp-server/pom.xml compile spotbugs:check
```
