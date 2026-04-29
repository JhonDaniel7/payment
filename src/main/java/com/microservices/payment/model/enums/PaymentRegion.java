package com.microservices.payment.model.enums;

/**
 * Market / region of the payment transaction.
 * Used for routing logic, compliance, and gateway selection.
 */
public enum PaymentRegion {

    /** Peruvian domestic market */
    PERU,

    /** Italian domestic market */
    ITALY,

    /** European Union cross-border (SEPA zone) */
    EUROPE_SEPA,

    /** Any other international transaction not covered above */
    INTERNATIONAL
}
