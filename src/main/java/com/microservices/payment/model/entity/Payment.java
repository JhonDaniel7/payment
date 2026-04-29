package com.microservices.payment.model.entity;

import com.microservices.payment.model.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Core entity representing a payment transaction.
 *
 * <p>Designed for reuse across multiple markets (Peru, Italy, Europe).
 * All sensitive card data is stored masked (PCI-DSS compliant).
 * Every state transition is tracked with a dedicated timestamp.
 */
@Entity
@Table(
    name = "payments",
    indexes = {
        @Index(name = "idx_payments_order_id",          columnList = "order_id"),
        @Index(name = "idx_payments_user_id",           columnList = "user_id"),
        @Index(name = "idx_payments_status",            columnList = "status"),
        @Index(name = "idx_payments_payment_reference", columnList = "payment_reference", unique = true),
        @Index(name = "idx_payments_transaction_id",    columnList = "transaction_id"),
        @Index(name = "idx_payments_region",            columnList = "payment_region")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"cardHolderName", "ipAddress", "userAgent"})
public class Payment {

    // ─────────────────────────────────────────────────────────
    // IDENTITY & TRACKING
    // ─────────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Human-readable unique reference shown to the customer (e.g. PAY-2024-00123).
     */
    @Column(name = "payment_reference", nullable = false, unique = true, length = 50)
    private String paymentReference;

    /**
     * Transaction ID returned by the payment gateway after processing.
     */
    @Column(name = "transaction_id", length = 255)
    private String transactionId;

    /**
     * Optional reference from the external system (e.g. order service, ERP).
     */
    @Column(name = "external_reference", length = 255)
    private String externalReference;

    // ─────────────────────────────────────────────────────────
    // PARTIES INVOLVED
    // ─────────────────────────────────────────────────────────

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    /**
     * Merchant identifier — useful for multi-merchant platforms.
     */
    @Column(name = "merchant_id")
    private UUID merchantId;

    // ─────────────────────────────────────────────────────────
    // FINANCIAL DATA
    // ─────────────────────────────────────────────────────────

    /**
     * Gross amount requested by the customer (before fees/taxes).
     */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /**
     * Currency of the transaction (ISO 4217).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 10)
    private Currency currency;

    /**
     * Exchange rate applied if the transaction involves currency conversion.
     * e.g. PEN → USD. Null if no conversion was needed.
     */
    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    /**
     * Amount converted to the merchant's base currency (after exchange rate applied).
     */
    @Column(name = "amount_in_base_currency", precision = 19, scale = 2)
    private BigDecimal amountInBaseCurrency;

    /**
     * Tax amount included in the gross amount (e.g. IVA/IGV).
     */
    @Column(name = "tax_amount", precision = 19, scale = 2)
    private BigDecimal taxAmount;

    /**
     * Net amount = amount - taxAmount.
     */
    @Column(name = "net_amount", precision = 19, scale = 2)
    private BigDecimal netAmount;

    /**
     * Gateway processing fee charged to the merchant.
     */
    @Column(name = "fee_amount", precision = 19, scale = 2)
    private BigDecimal feeAmount;

    // ─────────────────────────────────────────────────────────
    // PAYMENT METHOD & ROUTING
    // ─────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 40)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_gateway", length = 30)
    private PaymentGateway paymentGateway;

    /**
     * Geographic market of this transaction — drives gateway routing and compliance.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_region", nullable = false, length = 20)
    private PaymentRegion paymentRegion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    // ─────────────────────────────────────────────────────────
    // CARD DETAILS (PCI-DSS COMPLIANT — MASKED)
    // ─────────────────────────────────────────────────────────

    /**
     * Last 4 digits only — e.g. "4242". Never store full card number.
     */
    @Column(name = "masked_card_number", length = 4)
    private String maskedCardNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_brand", length = 20)
    private CardBrand cardBrand;

    /**
     * Name on the card — stored for dispute resolution purposes.
     */
    @Column(name = "card_holder_name", length = 150)
    private String cardHolderName;

    /**
     * Card expiry in MM/YY format — stored for reference only.
     */
    @Column(name = "card_expiry", length = 5)
    private String cardExpiry;

    // ─────────────────────────────────────────────────────────
    // TIMESTAMPS — full lifecycle traceability
    // ─────────────────────────────────────────────────────────

    /** Timestamp when the payment record was created in the system. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Timestamp of the last update to this record. */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** When the payment was submitted to the gateway. */
    @Column(name = "initiated_at")
    private LocalDateTime initiatedAt;

    /** When the payment was authorized (funds reserved). */
    @Column(name = "authorized_at")
    private LocalDateTime authorizedAt;

    /** When the funds were captured (separate capture flow). */
    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    /** When the payment was fully settled/completed. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** When the payment was cancelled. */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** When the payment or session link expires. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    // ─────────────────────────────────────────────────────────
    // REFUND TRACKING
    // ─────────────────────────────────────────────────────────

    @Column(name = "refund_amount", precision = 19, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refund_transaction_id", length = 255)
    private String refundTransactionId;

    @Column(name = "refund_reason", length = 500)
    private String refundReason;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    // ─────────────────────────────────────────────────────────
    // ERROR & FAILURE TRACKING
    // ─────────────────────────────────────────────────────────

    /** Short machine-readable error code returned by the gateway (e.g. "INSUFFICIENT_FUNDS"). */
    @Column(name = "failure_code", length = 100)
    private String failureCode;

    /** Human-readable failure reason. */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /** Raw error message from the gateway — useful for debugging. */
    @Column(name = "gateway_error_message", length = 1000)
    private String gatewayErrorMessage;

    /** Number of times this payment was retried after a failure. */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    // ─────────────────────────────────────────────────────────
    // 3DS / SCA (required in Europe under PSD2)
    // ─────────────────────────────────────────────────────────

    /**
     * 3D Secure authentication result (e.g. Y=authenticated, N=not authenticated, A=attempted).
     */
    @Column(name = "three_ds_status", length = 20)
    private String threeDsStatus;

    /** Transaction ID of the 3DS authentication exchange. */
    @Column(name = "three_ds_transaction_id", length = 255)
    private String threeDsTransactionId;

    // ─────────────────────────────────────────────────────────
    // AUDIT / SECURITY
    // ─────────────────────────────────────────────────────────

    /** IP address of the payer at the time of the transaction — for fraud detection. */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /** User-Agent of the payer's browser/app — for fraud detection. */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /** Device fingerprint or identifier — for fraud detection. */
    @Column(name = "device_id", length = 255)
    private String deviceId;

    // ─────────────────────────────────────────────────────────
    // DESCRIPTIVE / METADATA
    // ─────────────────────────────────────────────────────────

    /** Short description of what the payment is for (shown to the customer). */
    @Column(name = "description", length = 500)
    private String description;

    /** Internal notes visible only to the merchant/admin — not shown to the customer. */
    @Column(name = "notes", length = 1000)
    private String notes;

    /**
     * Arbitrary JSON metadata — store extra gateway-specific or business data
     * without needing schema changes (e.g. {"yape_phone": "+51999999999"}).
     */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;
}
