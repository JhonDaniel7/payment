package com.microservices.payment.exception;

/** The webhook request is not signed by Stripe with our signing secret. Maps to 400. */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
