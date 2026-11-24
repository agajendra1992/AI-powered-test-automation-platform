@visual
Feature: Visual appearance of the admin application
  As a QA Shop administrator
  I want pages to look the way they were approved
  So that layout and styling regressions are caught before release

  # Compared with approved screenshots in src/test/resources/visual-baselines/<browser>-<os>/.
  # Regions that change between runs (counts) are masked. See docs/VISUAL_TESTING.md.

  Background:
    Given the application is available

  @regression @ui
  Scenario: The login page looks as approved
    When the user opens the login page
    Then the page should match the "login" visual baseline

  @regression @ui
  Scenario Outline: The <page> page looks as approved
    Given the administrator is signed in
    When the user opens the <page> page
    Then the page should match the "<baseline>" visual baseline

    Examples:
      | page      | baseline  |
      | dashboard | dashboard |
      | payment   | payment   |
      | files     | files     |
