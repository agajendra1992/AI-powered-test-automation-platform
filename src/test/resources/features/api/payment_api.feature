@api @payment
Feature: Payment API
  Behaviour of /api/payments, including refunds.

  Background:
    Given the payment API is available

  @smoke @positive @critical
  Scenario: Approve a valid card payment
    When I submit a valid card payment of 120.00 GBP
    Then the response status code should be 201
    And the payment status should be "APPROVED"
    And the response should match the "payment.schema.json" schema
    And the card number should not be returned in full

  @regression @negative
  Scenario: A declined card returns 402
    When I submit a declined card payment of 10.00 USD
    Then the response status code should be 402
    And the payment status should be "DECLINED"

  @regression @positive
  Scenario: Partially and then fully refund a payment
    Given an approved payment of 100.00 USD exists
    When I refund 40.00 of the payment
    Then the response status code should be 200
    And the payment status should be "PARTIALLY_REFUNDED"
    When I refund 60.00 of the payment
    Then the payment status should be "REFUNDED"

  @regression @negative
  Scenario: Refunding more than the captured amount is rejected
    Given an approved payment of 50.00 USD exists
    When I refund 75.00 of the payment
    Then the response status code should be 422

  @regression @negative
  Scenario Outline: Reject invalid payment amounts (<amount>)
    When I submit a valid card payment of <amount> USD
    Then the response status code should be 422

    Examples:
      | amount   |
      | 0        |
      | -5.00    |
      | 10000.01 |
