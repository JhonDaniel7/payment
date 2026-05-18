package com.microservices.payment.gateway;

import com.microservices.payment.dto.request.PaymentRequest;
import com.microservices.payment.model.enums.PaymentMethod;
import com.microservices.payment.model.enums.PaymentRegion;

import java.math.BigDecimal;

public interface PaymentGatewayPort {

    /**
     * Submits a new payment to the gateway.
     * Returns a normalized GatewayResponse regardless of the underlying provider.
     */
    GatewayResponse charge(PaymentRequest request);

    /**
     * Issues a full or partial refund on an existing gateway transaction.
     */
    GatewayResponse refund(String transactionId, BigDecimal amount, String reason);

    /**
     * Used by the factory to select the correct adapter for the given region and method.
     */
    boolean supports(PaymentRegion region, PaymentMethod method);
}