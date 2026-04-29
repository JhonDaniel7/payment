package com.microservices.payment.model.enums;

/**
 * Currencies relevant for Peru and Italy/Europe markets (ISO 4217).
 */
public enum Currency {

    // ─────────────────────────────────────────────
    // PERU
    // ─────────────────────────────────────────────
    /** Peruvian Sol — official currency of Peru */
    PEN,

    /** US Dollar — widely accepted in Peru for commerce */
    USD,

    // ─────────────────────────────────────────────
    // ITALY / EUROPE
    // ─────────────────────────────────────────────
    /** Euro — official currency of Italy and the Eurozone */
    EUR,

    /** Swiss Franc — used in Switzerland (common in EU cross-border payments) */
    CHF,

    /** British Pound Sterling — post-Brexit UK, still relevant for EU transfers */
    GBP
}
