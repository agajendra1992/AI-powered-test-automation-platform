package io.qaplatform.api.models;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Payment(String id, String status, BigDecimal amount, String currency, String last4,
                      String reference, BigDecimal refundedAmount, OffsetDateTime createdAt) {
}
