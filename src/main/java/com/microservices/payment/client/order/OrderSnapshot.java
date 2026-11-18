package com.microservices.payment.client.order;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Subset of order-service's OrderResponse that payment-service needs.
 * {@code total} is the server-side amount to charge — never trust an amount from the frontend.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderSnapshot(
        UUID orderId,
        String customerId,
        String status,
        String currency,
        BigDecimal total,
        Customer customer,
        ShippingAddress shippingAddress
) {

    public static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Customer(String email) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShippingAddress(String country) { }
}
