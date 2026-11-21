# Developer onboarding

## Day 1: run the tests

1. Install JDK 21+, Maven 3.9+ and Python 3.12. Docker is optional.
2. Start the demo application and run the smoke suite. The commands are in the README Quick start.
3. Open the report: `scripts/allure-report.sh`.
4. Run one suite headed so you can watch it: `mvn test -DsuiteXmlFile=testng-ui.xml -Dheadless=false -Dthreads=1`.

## Day 2: write a scenario

1. Pick a behaviour and find its feature under `src/test/resources/features/<domain>/`.
2. Reuse existing steps. List them with the MCP tool `get_step_definitions`, or check a sentence
   with `search_step_definitions mode=match`.
3. New step? Add it to the step class for that area. Keep it thin and put logic in a page object
   or service.
4. New UI? Add or extend a page object with role, label or test-id locators. Assertions belong in
   steps, not pages.
5. Tags: one layer tag (`@ui`/`@api`/`@e2e`), a scope tag (`@smoke`/`@regression`), the domain
   tag, and `@positive`/`@negative`.
6. Check it before review: `validate_feature_file`, or just run it:
   `mvn test -DsuiteXmlFile=testng-regression.xml -Dcucumber.filter.tags="@your-tag"`.
7. The build gate (Checkstyle) rejects JUnit, Selenium, Rest Assured, sleeps, empty catches and
   `System.out`. SpotBugs runs in CI.
8. Use [CODE_REVIEW_CHECKLIST.md](../CODE_REVIEW_CHECKLIST.md) for your PR.

## Day 3: the AI tools

* Connect Claude Code to the MCP server ([MCP.md](MCP.md)) and ask it to list scenarios, run the
  API suite, or analyse a failure.
* Turn on AI only if your team has a provider key ([AI.md](AI.md)). Everything works without it.
* Knowledge base: [RAG.md](RAG.md). Test selection and flaky analytics:
  [INTELLIGENT_QA.md](INTELLIGENT_QA.md).

## Where things are

| Need | Look in |
|---|---|
| Commands, configuration, troubleshooting | [README.md](../README.md) |
| How the parts fit together | [ARCHITECTURE.md](ARCHITECTURE.md), [ARCHITECTURE_DECISIONS.md](ARCHITECTURE_DECISIONS.md) |
| CI workflows | README → Continuous integration |
| Deploying, monitoring | [DEPLOYMENT.md](DEPLOYMENT.md) |
| When something breaks | [RUNBOOKS.md](RUNBOOKS.md) |
| Upgrading dependencies | [UPGRADING.md](UPGRADING.md) |
| What is done and verified | [IMPLEMENTATION_STATUS.md](../IMPLEMENTATION_STATUS.md) |
