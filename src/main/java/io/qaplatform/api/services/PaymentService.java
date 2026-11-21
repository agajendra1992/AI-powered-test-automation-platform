package io.qaplatform.api.services;

import io.qameta.allure.Step;
import io.qaplatform.api.client.ApiClient;
import io.qaplatform.api.models.PaymentRequest;
import io.qaplatform.api.models.RefundRequest;
import io.qaplatform.api.requests.ApiRequest;
import io.qaplatform.api.responses.ApiResponse;

import java.math.BigDecimal;

/** Business-level operations on /api/payments. */
public class PaymentService {

    private final ApiClient client;

    public PaymentService(ApiClient client) {
        this.client = client;
    }

    @Step("Submit payment")
    public ApiResponse pay(PaymentRequest request) {
        return client.send(ApiRequest.post("/api/payments").body(request).build());
    }

    public ApiResponse get(String paymentId) {
        return client.send(ApiRequest.get("/api/payments/{id}").pathParam("id", paymentId).build());
    }

    @Step("Refund {amount}")
    public ApiResponse refund(String paymentId, BigDecimal amount) {
        return client.send(ApiRequest.post("/api/payments/{id}/refund").pathParam("id", paymentId)
                .body(new RefundRequest(amount)).build());
    }
}
