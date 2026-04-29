package com.microservices.payment.model.enums;

/**
 * Payment gateways and processors supported for Peru and Italy/Europe.
 */
public enum PaymentGateway {

    // ─────────────────────────────────────────────
    // INTERNATIONAL (both markets)
    // ─────────────────────────────────────────────
    STRIPE,       // International — strong in Europe
    PAYPAL,       // International — available in both markets

    // ─────────────────────────────────────────────
    // PERU
    // ─────────────────────────────────────────────
    CULQI,        // Peruvian gateway — cards, Yape, PagoEfectivo
    NIUBIZ,       // VisaNet Peru — largest card acquirer in Peru
    IZIPAY,       // Peruvian POS and online gateway
    SAFETY_PAY,   // Cash/bank via agent network in Peru and LATAM

    // ─────────────────────────────────────────────
    // ITALY / EUROPE
    // ─────────────────────────────────────────────
    NEXI,         // Italian card processor (CartaSi, Nexi Pay)
    KLARNA,       // European BNPL gateway
    SCALAPAY,     // Italian BNPL gateway
    SATISPAY,     // Italian mobile payment network
    POSTEPAY,     // Poste Italiane payment system
    ADYEN         // Global acquirer — strong EU coverage (used by many enterprise clients)
}
