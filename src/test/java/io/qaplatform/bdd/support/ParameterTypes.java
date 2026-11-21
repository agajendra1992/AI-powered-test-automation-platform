package io.qaplatform.bdd.support;

import io.cucumber.java.ParameterType;
import io.qaplatform.core.config.ConfigManager;
import io.qaplatform.core.data.TestDataManager;

/**
 * Custom Cucumber parameter types. Feature files name an account or card by its role
 * ("the administrator", "declined"); the actual secret values are resolved here from
 * configuration and test data, never written into Gherkin.
 */
public class ParameterTypes {

    /** Login credentials resolved at runtime. */
    public record Credentials(String username, String password) {
        @Override
        public String toString() {
            return "Credentials[" + username + "]";
        }
    }

    /** Card data from testdata/payment_cards.json. */
    public record Card(String cardholder, String cardNumber, String expiry, String cvv) {
        @Override
        public String toString() {
            return "Card[****" + cardNumber.substring(Math.max(0, cardNumber.length() - 4)) + "]";
        }
    }

    @ParameterType("the administrator with a wrong password|the administrator|an unknown user|a user without credentials")
    public Credentials account(String role) {
        ConfigManager c = ConfigManager.get();
        return switch (role) {
            case "the administrator" -> new Credentials(c.get("auth.username"), c.get("auth.password"));
            case "the administrator with a wrong password" -> new Credentials(c.get("auth.username"), TestDataManager.generatedPassword());
            case "an unknown user" -> new Credentials("nobody." + TestDataManager.uniqueSuffix(), TestDataManager.generatedPassword());
            case "a user without credentials" -> new Credentials("", "");
            default -> throw new IllegalArgumentException("Unknown account role: " + role);
        };
    }

    @ParameterType("valid|declined|invalid number|expired|invalid cvv")
    public Card card(String kind) {
        return TestDataManager.load("payment_cards", "/" + kind, Card.class);
    }
}
