package com.microservices.payment.exception;

import java.util.UUID;

/** The order does not exist in order-service (or does not belong to the caller). Maps to 404. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID orderId) {
        super("Order not found: " + orderId);
    }
}
