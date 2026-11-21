@payment
Feature: Payment processing
  As a QA Shop operator
  I want to take card payments
  So that customers can pay for orders

  Background:
    Given the payment application is available
    And the customer is on the payment page

  @smoke @ui @positive @critical
  Scenario: Successfully complete a payment
    When the customer enters valid payment details for 49.99 USD
    And the customer confirms the payment
    Then the payment should be successful
    And the confirmation should be displayed

  @regression @ui @negative
  Scenario Outline: Reject a payment with <card> details
    When the customer enters <card> payment details for 20.00 EUR
    And the customer confirms the payment
    Then the payment should be rejected with "<message>"

    Examples:
      | card           | message                                    |
      | declined       | Payment declined by issuer                 |
      | invalid number | Payment rejected: invalid card number      |
      | expired        | Payment rejected: card has expired         |
      | invalid cvv    | Payment rejected: invalid CVV              |
