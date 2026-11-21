# Upgrading dependencies

All versions live in `pom.xml` `<properties>` (plus the Cucumber, Allure and Jackson BOMs).

1. Change one property at a time (e.g. `playwright.version`).
2. `mvn -B clean test -DsuiteXmlFile=testng-unit.xml`, which compiles everything and runs the framework tests.
3. `mvn -B clean test -DsuiteXmlFile=testng-regression.xml` on chromium, then `-Dbrowser=firefox` and `-Dbrowser=webkit`.
4. `scripts/allure-report.sh`, then check that the scenario count matches and nothing is duplicated.
5. Record the change and results in `IMPLEMENTATION_STATUS.md`.

Compatibility notes:
* `allure-cucumber8-jvm` must match the Cucumber 8.x line (Cucumber 7 needed `allure-cucumber7-jvm`); the runners name the plugin class `io.qameta.allure.cucumber8jvm.AllureCucumber8Jvm`.
* Cucumber 8's JSON report plugin needs `jackson-databind` + `jackson-datatype-jdk8` on the classpath (both from the Jackson BOM).
* `allure.commandline.version` (report generator) is versioned independently of `allure.version` (adapters).
* Playwright downloads browser builds matching its version on first run; CI caches `~/.cache/ms-playwright`
  (the cache key includes the `pom.xml` hash, so a version bump refreshes it).
* **Docker:** the `FROM` tag in `Dockerfile` (`mcr.microsoft.com/playwright/java:v<version>-noble`)
  must match `playwright.version`, or the bundled browsers will not match the driver.
  `python3 scripts/check_versions.py` checks this (CI runs it first). On a Dependabot Playwright
  PR, edit that one line in the same PR.
  Rebuild with `docker compose --profile tests build` and run the smoke suite in Docker.
* **GitHub Actions** are pinned to commit SHAs with the release tag in a comment
  (`actions/checkout@<sha> # v7.0.1`). Dependabot proposes these updates weekly. To upgrade by hand, resolve the new tag's commit
  (`gh api repos/actions/checkout/git/ref/tags/<tag>`; dereference annotated tags), replace
  the SHA and comment in every workflow, then lint:
  `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest .github/workflows/*.yml`.
* `json-schema-validator` is pinned to 1.5.x: 2.x/3.x changed the API used by `SchemaValidator`.

## MCP server (`mcp-server/pom.xml`)
* `gherkin`, `cucumber-expressions` and `tag-expressions` must match the versions in the
  framework's Cucumber BOM (the `cucumber-bom-<version>.pom` lists them). Dependabot ignores them
  in this module, so bump them together with `cucumber.version`.
* `playwright.version` must match the framework's; `scripts/check_versions.py` (run first in CI)
  fails on a mismatch.
* After an MCP SDK upgrade, run `mvn -f mcp-server/pom.xml package` and the stdio end-to-end test
  (`-Dtest=StdioFrameworkIT`). The SDK API changed between 0.x, 1.x and 2.x.
* `anthropic-java` (Claude provider): after an upgrade run `LlmProvidersTest`, which streams a
  recorded response through the SDK and checks the request it sends. SDK 3.x authenticates with
  `Authorization: Bearer` and pins `anthropic-version`.
* Prompt templates (`mcp-server/src/main/resources/prompts`) are versioned: bump `version:` when
  a prompt changes, so results can be traced to the prompt that produced them.

## Held-back upgrades (Dependabot ignore rules)

| Dependency | Held at | Why |
|---|---|---|
| maven-surefire-plugin | 3.5.x | 3.6+ runs TestNG on the JUnit Platform and rejects `suiteXmlFiles` |
| mcp (Python, ai-orchestration) | 1.x | langchain-mcp-adapters requires 1.x |

To try one of them, remove its rule from `.github/dependabot.yml`; Dependabot opens the PR on its next run.

### Done: Cucumber 7 → 8 and Allure 2 → 3 (2026-11-21)

Cucumber 8.0.4 and Allure 3.1.1 (8.1.0 was inside the 7-day cooldown). Changes: `BaseCucumberRunner.scenarios(ITestContext)`;
`AllureAttachments` uses `Allure.attachment(..., AttachmentOptions.withFileExtension(...))` and closes file streams;
adapter `allure-cucumber8-jvm`; `jackson-datatype-jdk8` added for Cucumber's JSON report. Verified: regression 37/37 on
chromium, firefox and webkit; failure evidence (screenshot, diagnostics, trace, API calls) still attached; Cucumber JSON
unchanged in shape; Allure HTML report builds with the pinned CLI. Allure results no longer carry a `testClass` label.

