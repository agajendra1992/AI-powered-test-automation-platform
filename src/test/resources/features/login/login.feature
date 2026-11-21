@login
Feature: Sign in to the admin application
  As a QA Shop administrator
  I want to sign in securely
  So that only authorised people can manage users and payments

  Background:
    Given the application is available
    And the user is on the login page

  @smoke @ui @positive @critical
  Scenario: Administrator signs in with valid credentials
    When the user signs in as the administrator
    Then the dashboard should welcome "QA Admin"
    And the signed-in user should be shown as "QA Admin"

  @regression @ui @negative
  Scenario Outline: Sign in is refused for <case>
    When the user signs in as <account>
    Then the login error "<message>" should be displayed
    And the user should remain on the login page

    Examples:
      | case                 | account                                  | message                           |
      | an unknown user      | an unknown user                          | Invalid username or password      |
      | a wrong password     | the administrator with a wrong password  | Invalid username or password      |
      | missing credentials  | a user without credentials               | Username and password are required |

  @regression @ui @positive
  Scenario: Administrator signs out
    Given the user signs in as the administrator
    When the user logs out
    Then the user should be on the login page
