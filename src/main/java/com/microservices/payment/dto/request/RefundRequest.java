package com.microservices.payment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;

@Value
@Builder
@Jacksonized
public class RefundRequest {

    @NotNull
    @DecimalMin(value = "0.01")
    BigDecimal refundAmount;

    @Size(max = 500)
    String refundReason;

    /** RMA number of the order-service return this refund pays out; stored on the Stripe refund. */
    @Size(max = 30)
    String returnReference;

    /** Stripe only: same key = same refund. Null = key derived from the payment's refund state. */
    @Size(max = 100)
    String idempotencyKey;
}
