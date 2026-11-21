package io.qaplatform.bdd.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.qaplatform.api.models.PaymentRequest;
import io.qaplatform.bdd.context.ScenarioContext;
import io.qaplatform.bdd.support.ParameterTypes.Card;
import io.qaplatform.ui.pages.PaymentPage;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** UI payment journey. API-level payment checks live in {@link PaymentApiSteps}. */
public class PaymentSteps {

    private final ScenarioContext context;

    public PaymentSteps(ScenarioContext context) {
        this.context = context;
    }

    @Given("the customer is on the payment page")
    public void onPaymentPage() {
        context.authenticateBrowserWithToken(context.adminToken());
        new PaymentPage(context.page()).open();
    }

    @When("the customer enters {card} payment details for {bigdecimal} {word}")
    public void entersDetails(Card card, BigDecimal amount, String currency) {
        new PaymentPage(context.page()).enter(new PaymentRequest(
                card.cardholder(), card.cardNumber(), card.expiry(), card.cvv(), amount, currency));
    }

    @When("the customer confirms the payment")
    public void confirms() {
        new PaymentPage(context.page()).confirm();
    }

    @Then("the payment should be successful")
    public void successful() {
        assertThat(new PaymentPage(context.page()).success()).isVisible();
    }

    @Then("the confirmation should be displayed")
    public void confirmationDisplayed() {
        assertThat(new PaymentPage(context.page()).success())
                .hasText(Pattern.compile("^Payment approved\\. Reference PAY-[A-F0-9]{8}$"));
    }

    @Then("the payment should be rejected with {string}")
    public void rejected(String message) {
        PaymentPage page = new PaymentPage(context.page());
        assertThat(page.error()).hasText(message);
        assertThat(page.success()).isHidden();
    }
}
