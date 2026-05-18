package com.microservices.payment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class RefundRequest {

    @NotNull
    @DecimalMin(value = "0.01")
    BigDecimal refundAmount;

    @Size(max = 500)
    String refundReason;
}