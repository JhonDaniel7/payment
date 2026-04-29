package com.microservices.payment.model.enums;

/**
 * Card brand / network for credit and debit card payments.
 * Relevant for both Peruvian (Visa/Mastercard dominant) and European markets.
 */
public enum CardBrand {

    // ─── Global ───────────────────────────────────────
    VISA,
    MASTERCARD,
    AMERICAN_EXPRESS,
    DINERS_CLUB,
    DISCOVER,
    JCB,            // Japan Credit Bureau — accepted globally
    UNIONPAY,       // China UnionPay — growing globally

    // ─── Italy specific ───────────────────────────────
    BANCOMAT,       // Italian national debit card network (PagoBancomat)
    NEXI,           // Nexi-issued cards (ex CartaSi)

    UNKNOWN
}
