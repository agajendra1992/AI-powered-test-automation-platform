@e2e @ui @api
Feature: Customer registration
  Customers created through the API can use the application, and changes made in the UI
  are visible through the API.

  @regression @critical
  Scenario: Create customer and verify profile
    Given a customer is created through the API
    When the customer logs into the application
    Then the customer profile should be displayed
    And the profile details should match the API response

  @regression @critical
  Scenario: Profile updated in the UI is visible through the API
    Given a customer is created through the API
    And the administrator is signed in through an API token
    When the administrator changes the customer's job to "Staff Engineer" in the UI
    Then the API should return job "Staff Engineer" for the customer
