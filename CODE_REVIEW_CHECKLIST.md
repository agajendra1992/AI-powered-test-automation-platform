# Code Review Checklist

## Gherkin
- [ ] Business language; no selectors, URLs, credentials or card numbers in feature files
- [ ] Correct tags: one scope (`@smoke`/`@regression`), one layer (`@ui`/`@api`/`@e2e`), domain, intent
- [ ] Scenario is independent: creates its own data, no ordering assumptions
- [ ] Scenario Outline only when examples vary data, not behaviour

## Step definitions
- [ ] Thin: delegates to page objects / services / fixtures
- [ ] No duplicate step text (Cucumber ignores Given/When/Then)
- [ ] Cucumber expressions or parameter types, not broad regexes
- [ ] State shared only through `ScenarioContext` (typed `Keys`), never static fields

## Page objects and components
- [ ] Locator priority: role → label → placeholder → text → test id → CSS; no XPath without justification
- [ ] No assertions and no test data inside page objects
- [ ] Repeated widgets are components, composed rather than inherited
- [ ] No `Thread.sleep` / `waitForTimeout` (Checkstyle enforces); waits target real conditions

## API
- [ ] Calls go through a service; requests and responses are separate models
- [ ] Status, schema and key fields asserted; negative cases covered
- [ ] Test data created via API is registered for cleanup

## Security
- [ ] No secrets in properties, features, test data or logs
- [ ] No `@Step` on methods that take a password, token or secret
- [ ] New sensitive JSON fields added to `Redactor` with a unit test

## Execution and reporting
- [ ] Passes locally with `-Dthreads=4` and on at least one non-Chromium browser for UI changes
- [ ] Retries not used to hide a real defect; flaky scenarios tagged `@quarantine` with a ticket
- [ ] `IMPLEMENTATION_STATUS.md` updated when capabilities change

## CI and containers
- [ ] New workflow steps get inputs through `env:`, never `${{ inputs.* }}` / `${{ github.event.* }}` inside `run:`.
- [ ] Third-party actions are pinned to a commit SHA with the tag in a comment.
- [ ] Workflows keep `permissions: contents: read` unless a job truly needs more (state why).
- [ ] Secrets are read only in the step that needs them, from GitHub Environments for qa/staging; nothing echoes them.
- [ ] No fixed waits in workflows: poll a health endpoint (`curl --retry`) instead of `sleep`.
- [ ] actionlint passes (`docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest .github/workflows/*.yml`).
- [ ] Dockerfile base image tag matches `playwright.version` (`python3 scripts/check_versions.py`); images run as a non-root user.
- [ ] New schedules state their time zone (`timezone:`) and default to the demo app unless `SCHEDULED_TEST_ENV` says otherwise.

## MCP server
- [ ] New tools declare the lowest role that is safe (viewer for reads) and `readOnly` correctly; anything that changes state is runner or above.
- [ ] Tool arguments are in the closed schema and validated through `Args` with an allow-list pattern; free text never reaches a command line unquoted.
- [ ] Nothing builds a shell command. Runs go through `ExecutionRequest.mavenArguments()` only.
- [ ] Tool output carries no secrets: rely on the `GuardedTools` redaction, and never return raw config or environment variables.
- [ ] A tool never reports a result the framework did not produce (no "passed" without `execution-summary.json`).
- [ ] Sensitive actions keep the two-person rule (requester ≠ approver) and are audited.

## AI layer
- [ ] Every AI feature has a deterministic result that is returned when the model is off, slow or wrong.
- [ ] Model output is validated against the project (tags, environments, categories, scenario names, compile/bind checks) before use.
- [ ] Untrusted text reaches prompts only through `PromptManager.untrusted(...)` (redacted, fenced, length-limited).
- [ ] Nothing AI-generated is written into `src/` except through `accept_generated_draft` (admin, no overwrite).
- [ ] AI text is labelled as a hypothesis or suggestion and never alters a test status.
- [ ] Prompt changes bump the template `version:`.
