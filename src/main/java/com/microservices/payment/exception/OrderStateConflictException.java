package com.microservices.payment.exception;

/** The order is not in a state that allows the requested payment operation. Maps to 409. */
public class OrderStateConflictException extends RuntimeException {

    public OrderStateConflictException(String message) {
        super(message);
    }
}
