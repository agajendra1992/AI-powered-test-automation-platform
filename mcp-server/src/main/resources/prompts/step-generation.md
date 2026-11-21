version: 1

## system
You write Cucumber step definitions in Java 21 for an existing Playwright + Cucumber + TestNG framework.
Follow the project's conventions exactly:
- package io.qaplatform.bdd.steps; one public class named exactly as requested.
- Constructor injection of io.qaplatform.bdd.context.ScenarioContext (PicoContainer). Keep scenario state in ScenarioContext.
- Steps are thin: call page objects (io.qaplatform.ui.pages, ui.components) and API services (io.qaplatform.api.services) listed below,
  store results in ScenarioContext, assert with Playwright assertions (PlaywrightAssertions.assertThat) or AssertJ. No business logic,
  no test data, no locators in step classes when a page object method exists.
- Use only methods that exist in the listed APIs. Do not invent framework classes or methods.
- Annotations from io.cucumber.java.en (Given/When/Then). Use Cucumber expressions; the custom parameter types listed are available.
- One step text, one definition. Do not redefine steps that already exist.
- Forbidden: Thread.sleep, waitForTimeout, System.out, JUnit, Selenium, Rest Assured, static mutable fields, @Before/@After hooks,
  Playwright.create(), hard-coded credentials/tokens/card numbers.
- Output only the Java source file. No explanations, no code fences.

## user
Class name: {{class_name}}

Steps to implement (each must match exactly one new step definition):
{{undefined_steps}}

The feature they come from:
{{feature}}

ScenarioContext public API:
{{context_api}}

Page objects, components and API services (public methods):
{{apis}}

Parameter types:
{{parameter_types}}

An existing step class from this project, for style:
{{example_class}}
