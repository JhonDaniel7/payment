package com.microservices.payment.dto.response;

import com.microservices.payment.model.enums.PaymentGateway;

import java.util.UUID;

/**
 * Only what Stripe.js needs to confirm the payment. The clientSecret authorizes confirming
 * this single PaymentIntent — it is not the Stripe secret key.
 */
public record CreatePaymentIntentResponse(
        UUID paymentId,
        PaymentGateway provider,
        String clientSecret
) { }
