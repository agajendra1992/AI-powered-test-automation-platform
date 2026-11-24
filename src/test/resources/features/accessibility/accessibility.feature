@accessibility
Feature: Accessibility of the admin application
  As a QA Shop administrator who may use a keyboard or a screen reader
  I want every page to meet WCAG 2.1 level AA
  So that the application is usable by everyone

  # axe-core rules for WCAG 2.0/2.1 A and AA. Serious and critical violations fail;
  # minor and moderate ones are listed in the report (config: a11y.*).

  Background:
    Given the application is available

  @smoke @regression @ui
  Scenario: The login page has no serious accessibility violations
    When the user opens the login page
    Then the page should pass the accessibility checks

  @regression @ui
  Scenario Outline: The <page> page has no serious accessibility violations
    Given the administrator is signed in
    When the user opens the <page> page
    Then the page should pass the accessibility checks

    Examples:
      | page            |
      | dashboard       |
      | user management |
      | payment         |
      | files           |
