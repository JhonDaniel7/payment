package com.microservices.payment.model.enums;

/**
 * Payment methods available for Peru and Italy/Europe markets.
 */
public enum PaymentMethod {

    // ─────────────────────────────────────────────
    // COMMON (both markets)
    // ─────────────────────────────────────────────
    CREDIT_CARD,         // Visa, Mastercard, Amex
    DEBIT_CARD,          // Generic debit card
    PAYPAL,              // Available in both markets
    APPLE_PAY,           // iOS digital wallet
    GOOGLE_PAY,          // Android digital wallet

    // ─────────────────────────────────────────────
    // PERU
    // ─────────────────────────────────────────────

    /** Yape — BCP mobile payment app, most popular in Peru */
    YAPE,

    /** Plin — mobile payment alliance (BBVA, Interbank, Scotiabank, BanBif) */
    PLIN,

    /** PagoEfectivo — cash voucher payment at agents (KasNet, Western Union, etc.) */
    PAGO_EFECTIVO,

    /** SafetyPay — cash or bank transfer via agent network */
    SAFETY_PAY,

    /** Niubiz (formerly VisaNet Peru) — main card acquiring gateway in Peru */
    NIUBIZ,

    /** Culqi — Peruvian fintech payment gateway (cards, Yape, PagoEfectivo) */
    CULQI,

    /** Izipay — Peruvian payment gateway managed by Izipay SA */
    IZIPAY,

    /** BCP (Banco de Crédito del Perú) direct bank transfer */
    BCP_TRANSFER,

    /** Interbank direct bank transfer */
    INTERBANK_TRANSFER,

    /** BBVA Peru direct bank transfer */
    BBVA_TRANSFER_PE,

    /** Scotiabank Peru direct bank transfer */
    SCOTIABANK_TRANSFER,

    // ─────────────────────────────────────────────
    // ITALY / EUROPE
    // ─────────────────────────────────────────────

    /** SEPA Credit Transfer — EU standard bank transfer */
    SEPA_CREDIT_TRANSFER,

    /** SEPA Direct Debit — recurring EU bank debit */
    SEPA_DIRECT_DEBIT,

    /** Bonfico bancario — Italian classic bank wire */
    BONIFICO,

    /** Bancomat / PagoBancomat — Italian national debit card network */
    BANCOMAT,

    /** Nexi — Italian card payment processor (formerly CartaSi) */
    NEXI,

    /** PostePay — Italian prepaid card by Poste Italiane */
    POSTEPAY,

    /** Satispay — Italian mobile payment app */
    SATISPAY,

    /** Scalapay — Italian BNPL (Buy Now Pay Later) */
    SCALAPAY,

    /** Klarna — European BNPL (Pay Later, Slice It) */
    KLARNA,

    /** Stripe — international gateway, widely used in Europe */
    STRIPE,

    /** Amazon Pay — available across EU */
    AMAZON_PAY
}
