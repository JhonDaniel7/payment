package com.microservices.payment.dto.request;

import com.microservices.payment.model.enums.PaymentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PaymentStatusUpdateRequest {

    @NotNull
    PaymentStatus status;

    @Size(max = 100)
    String failureCode;

    @Size(max = 500)
    String failureReason;

    @Size(max = 255)
    String transactionId;

    @Size(max = 1000)
    String notes;
}