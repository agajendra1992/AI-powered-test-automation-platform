@user
Feature: User management
  As an administrator
  I want to add, find and remove users
  So that the user directory stays accurate

  Background:
    Given the administrator is signed in

  @regression @ui @positive
  Scenario: Add a user through the user management screen
    Given the administrator is on the user management page
    When the administrator adds a user with:
      | name  | Grace Hopper   |
      | job   | Rear Admiral   |
    Then the new user should be listed with job "Rear Admiral"

  @regression @ui @negative
  Scenario: Adding a user with an invalid email is refused
    Given the administrator is on the user management page
    When the administrator adds a user with:
      | name  | No Email User  |
      | email | not-an-email   |
      | job   | Tester         |
    Then the add user form should show an error containing "email"

  @regression @ui @positive
  Scenario: Search and delete a user
    Given a user exists in the system
    And the administrator is on the user management page
    When the administrator searches for that user
    Then exactly 1 user should be listed
    When the administrator deletes that user and confirms
    Then the user list should be empty

  @regression @ui @negative
  Scenario: Dashboard shows an error when user data cannot be loaded
    Given the user service is failing
    When the administrator opens the dashboard
    Then the dashboard should show a data loading error

  @regression @ui @positive
  Scenario: Upload a file and download the users report
    Given a user exists in the system
    When the administrator uploads the file "sample-upload.txt"
    Then the upload should be confirmed for "sample-upload.txt"
    And the downloaded users report should contain that user
