package com.microservices.payment.exception;

/** order-service is unreachable or answered with an unexpected error. Maps to 502. */
public class OrderServiceException extends RuntimeException {

    public OrderServiceException(String message) {
        super(message);
    }

    public OrderServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
