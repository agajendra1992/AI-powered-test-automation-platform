package io.qaplatform.unit;

import io.qaplatform.core.reporting.Redactor;
import org.testng.annotations.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class RedactorTest {

    @Test
    public void masksSecretJsonFields() {
        String out = Redactor.text("{\"username\":\"a\",\"password\":\"p@ss\",\"token\":\"abc\",\"client_secret\":\"s\"}");
        assertThat(out).contains("\"username\":\"a\"").doesNotContain("p@ss").doesNotContain("abc").doesNotContain("\"s\"");
    }

    @Test
    public void keepsOnlyLastFourCardDigitsAndMasksCvv() {
        String out = Redactor.text("{\"cardNumber\":\"4242424242424242\",\"cvv\":\"123\"}");
        assertThat(out).contains("\"cardNumber\":\"****4242\"").doesNotContain("424242424242").contains("\"cvv\":\"****\"");
    }

    @Test
    public void masksBearerTokensInFreeText() {
        assertThat(Redactor.text("Authorization: Bearer eyJhbGciOi.payload.sig")).isEqualTo("Authorization: Bearer ****");
    }

    @Test
    public void masksSensitiveHeadersCaseInsensitively() {
        Map<String, String> out = Redactor.headers(Map.of("AUTHORIZATION", "Basic x", "X-Correlation-Id", "c-1"));
        assertThat(out).containsEntry("AUTHORIZATION", "****").containsEntry("X-Correlation-Id", "c-1");
    }

    @Test
    public void nullIsNull() {
        assertThat(Redactor.text(null)).isNull();
    }
}
