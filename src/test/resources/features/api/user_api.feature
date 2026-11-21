@api @user
Feature: User Management API
  Contract and behaviour of /api/users.

  Background:
    Given the user API is available

  @smoke @positive @critical
  Scenario: Create a new user
    When I send a POST request to create a user
    Then the response status code should be 201
    And the response should contain the user ID
    And the response should match the "user.schema.json" schema
    And the response should echo the correlation id
    And the response time should be within the agreed limit

  @regression @positive
  Scenario: Retrieve an existing user
    Given an existing user is available
    When I send a GET request for the user
    Then the response status code should be 200
    And the user details should be correct

  @regression @positive
  Scenario: Update a user with PUT and PATCH
    Given an existing user is available
    When I replace the user's job with "Architect"
    Then the response status code should be 200
    When I patch the user's name to "Patched Name"
    Then the response status code should be 200
    And the user should have name "Patched Name" and job "Architect"

  @regression @positive
  Scenario: Delete a user
    Given an existing user is available
    When I delete the user
    Then the response status code should be 204
    When I send a GET request for the user
    Then the response status code should be 404

  @regression @positive
  Scenario: List users with pagination
    Given an existing user is available
    When I list users with page 1 and size 5
    Then the response status code should be 200
    And the response should match the "user-list.schema.json" schema

  @regression @negative
  Scenario Outline: Reject invalid user payloads (<case>)
    When I send a POST request to create a user with name "<name>" and email "<email>"
    Then the response status code should be <status>

    Examples:
      | case          | name      | email                 | status |
      | missing name  |           | valid@qa.example.com  | 422    |
      | invalid email | Some Name | not-an-email          | 422    |

  @regression @negative
  Scenario: Duplicate email is rejected
    Given an existing user is available
    When I create another user with the same email
    Then the response status code should be 409

  @regression @negative
  Scenario Outline: Requests without valid credentials are rejected (<auth>)
    When I request the user list with <auth>
    Then the response status code should be 401

    Examples:
      | auth                 |
      | no authentication    |
      | an invalid token     |

  @regression @positive
  Scenario Outline: Supported authentication schemes are accepted (<auth>)
    When I request the user list with <auth>
    Then the response status code should be 200

    Examples:
      | auth                 |
      | basic authentication |
      | an OAuth2 token      |

  @regression @negative
  Scenario: Requesting an unknown user returns 404
    When I request the user with id "does-not-exist"
    Then the response status code should be 404
