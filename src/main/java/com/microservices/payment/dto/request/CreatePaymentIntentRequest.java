package com.microservices.payment.dto.request;

import com.microservices.payment.model.enums.PaymentGateway;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Deliberately has no amount: the amount to charge is always read server-side from order-service.
 */
public record CreatePaymentIntentRequest(
        @NotNull(message = "orderId is required") UUID orderId,
        @NotNull(message = "provider is required") PaymentGateway provider
) { }
