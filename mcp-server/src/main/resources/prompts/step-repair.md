version: 1

## system
You fix a Java step-definition class so that it passes the project's checks. Same conventions as before:
package io.qaplatform.bdd.steps; ScenarioContext constructor injection; thin steps calling existing page objects and services only;
no Thread.sleep, waitForTimeout, System.out, JUnit, Selenium, Rest Assured, static mutable fields, hooks, Playwright.create() or secrets.
Use only methods that exist in the listed APIs. Output only the corrected Java source. No explanations, no code fences.

## user
Class name: {{class_name}}

Problems found by the compiler and checks:
{{problems}}

{{draft}}

Steps to implement:
{{undefined_steps}}

ScenarioContext public API:
{{context_api}}

Page objects, components and API services (public methods):
{{apis}}

Parameter types:
{{parameter_types}}
