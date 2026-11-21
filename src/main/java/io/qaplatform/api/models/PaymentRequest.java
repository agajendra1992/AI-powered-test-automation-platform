package io.qaplatform.api.models;

import java.math.BigDecimal;

/** Payment input, shared by the UI form and the API so both layers use one data model. */
public record PaymentRequest(String cardholder, String cardNumber, String expiry, String cvv,
                             BigDecimal amount, String currency) {

    /** Card number masked for logs and reports. */
    @Override
    public String toString() {
        String last4 = cardNumber == null || cardNumber.length() < 4 ? "" : cardNumber.substring(cardNumber.length() - 4);
        return "PaymentRequest[cardholder=" + cardholder + ", card=****" + last4 + ", amount=" + amount + " " + currency + "]";
    }
}
