package com.microservices.payment.model.enums;

/**
 * Full lifecycle of a payment transaction.
 * Covers both Peruvian and Italian/European payment flows.
 */
public enum PaymentStatus {

    // --- Initial states ---
    PENDING,             // created, not yet submitted to gateway
    INITIATED,           // submitted to gateway, awaiting response

    // --- Authentication / authorization ---
    REQUIRES_ACTION,     // 3DS / SCA challenge required (EU PSD2 compliance)
    REQUIRES_CAPTURE,    // authorized but not yet captured (pre-auth)
    AUTHORIZED,          // funds reserved, capture pending

    // --- Processing states ---
    PROCESSING,          // gateway is processing the transaction
    ON_HOLD,             // flagged for manual or anti-fraud review

    // --- Terminal success states ---
    CAPTURED,            // funds captured (separate capture flow)
    COMPLETED,           // fully settled

    // --- Refund states ---
    REFUND_PENDING,      // refund requested, awaiting gateway confirmation
    PARTIALLY_REFUNDED,  // partial refund issued
    REFUNDED,            // full refund issued

    // --- Failure states ---
    DECLINED,            // declined by bank or card issuer
    FAILED,              // technical or gateway failure
    EXPIRED,             // payment link/session expired before completion
    CANCELLED,           // cancelled by user or merchant

    // --- Dispute states ---
    CHARGEBACK,          // customer opened a chargeback with their bank
    CHARGEBACK_WON,      // merchant won the dispute
    CHARGEBACK_LOST      // merchant lost the dispute
}
