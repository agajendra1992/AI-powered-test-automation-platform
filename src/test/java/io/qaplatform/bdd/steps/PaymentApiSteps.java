package io.qaplatform.bdd.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.qaplatform.api.models.Payment;
import io.qaplatform.api.models.PaymentRequest;
import io.qaplatform.api.responses.ApiResponse;
import io.qaplatform.bdd.context.Keys;
import io.qaplatform.bdd.context.ScenarioContext;
import io.qaplatform.bdd.support.ParameterTypes.Card;
import io.qaplatform.core.exceptions.FrameworkException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

public class PaymentApiSteps {

    private final ScenarioContext context;

    public PaymentApiSteps(ScenarioContext context) {
        this.context = context;
    }

    @When("I submit a {card} card payment of {bigdecimal} {word}")
    public void submit(Card card, BigDecimal amount, String currency) {
        PaymentRequest request = new PaymentRequest(card.cardholder(), card.cardNumber(), card.expiry(), card.cvv(), amount, currency);
        context.lastResponse(context.payments().pay(request));
    }

    @Given("an approved payment of {bigdecimal} {word} exists")
    public void approvedPayment(BigDecimal amount, String currency) {
        submit(new Card("Test Customer", "4242424242424242", "12/30", "123"), amount, currency);
        ApiResponse r = context.lastResponse();
        if (r.status() != 201) {
            throw new FrameworkException("Test data setup failed: payment returned HTTP " + r.status());
        }
        context.put(Keys.PAYMENT, r.as(Payment.class));
    }

    @When("I refund {bigdecimal} of the payment")
    public void refund(BigDecimal amount) {
        context.lastResponse(context.payments().refund(context.get(Keys.PAYMENT).id(), amount));
    }

    @Then("the payment status should be {string}")
    public void status(String expected) {
        assertThat(context.lastResponse().at("/status")).contains(expected);
    }

    @Then("the card number should not be returned in full")
    public void cardMasked() {
        assertThat(context.lastResponse().body()).doesNotContain("4242424242424242");
        assertThat(context.lastResponse().at("/last4")).contains("4242");
    }
}
