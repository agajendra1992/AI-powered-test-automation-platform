# Accessibility testing

Every page of the demo app is scanned with [axe-core](https://github.com/dequelabs/axe-core)
(Deque's `com.deque.html.axe-core:playwright`), the rule engine most accessibility tools use.

## What runs

| Feature | Scope | Tags |
|---|---|---|
| `features/accessibility/accessibility.feature` | login (smoke), dashboard, user management, payment, files | `@accessibility @regression @ui` |

Each scenario opens the page, waits until it has finished rendering (`WaitUtils.stableRender`),
runs axe and fails on any violation at or above the configured impact.

```bash
mvn test -DsuiteXmlFile=testng-regression.xml -Dcucumber.filter.tags="@accessibility"
```

## Policy (`config/default.properties`)

| Key | Default | Meaning |
|---|---|---|
| `a11y.tags` | `wcag2a,wcag2aa,wcag21a,wcag21aa` | axe rule sets: WCAG 2.0 and 2.1, levels A and AA |
| `a11y.fail.impact` | `serious` | serious and critical violations fail; minor and moderate are reported only |
| `a11y.disabled.rules` | (empty) | axe rule ids to skip. Add one only with a reason in the table below |

Disabled rules and why: none.

## Reading a failure

The Allure report has two attachments per scan:

* **Accessibility report**: rules passed, each violation with its impact, rule id, help text, link to
  Deque's explanation, and the CSS selectors of the failing elements.
* **axe-core violations** (JSON): the raw axe output, with secrets masked by `Redactor`.

The same summary is the assertion message, so CI logs show it too.

## Checking new pages

Add the page to `AppPages` (test support) and a row to the scenario outline's examples.

## Limits

Automated rules find roughly a third to a half of WCAG issues. Keyboard order, screen-reader wording
and meaningful alternative text still need a manual review; axe's "incomplete" results (needs review)
are not reported yet.

## Verification (2026-11-24)

* 5/5 scenarios pass on chromium; each scan ran 11 to 19 rules with 0 violations.
* With the login button's text removed and an image without `alt` added to the demo app, the login
  scenario failed with `[critical] button-name` and `[critical] image-alt`. The demo app was restored afterwards.
