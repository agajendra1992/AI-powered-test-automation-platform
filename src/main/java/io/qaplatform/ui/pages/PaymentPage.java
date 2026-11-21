package io.qaplatform.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.qameta.allure.Step;
import io.qaplatform.api.models.PaymentRequest;
import io.qaplatform.ui.components.DropdownComponent;

public class PaymentPage extends BasePage {

    public PaymentPage(Page page) {
        super(page);
    }

    @Override
    protected String path() {
        return "/payments/new";
    }

    @Step("Open the payment page")
    public PaymentPage open() {
        navigate();
        return this;
    }

    /** Fills the form from a payment request (the same model the API layer uses). */
    @Step("Enter payment details")
    public PaymentPage enter(PaymentRequest details) {
        field("Cardholder name").fill(details.cardholder());
        field("Card number").fill(details.cardNumber());
        field("Expiry (MM/YY)").fill(details.expiry());
        field("CVV").fill(details.cvv());
        field("Amount").fill(details.amount() == null ? "" : details.amount().toPlainString());
        new DropdownComponent(page, "Currency").choose(details.currency());
        return this;
    }

    @Step("Confirm the payment")
    public void confirm() {
        button("Pay now").click();
    }

    public Locator success() {
        return page.getByTestId("payment-success");
    }

    public Locator error() {
        return page.getByTestId("payment-error");
    }
}
