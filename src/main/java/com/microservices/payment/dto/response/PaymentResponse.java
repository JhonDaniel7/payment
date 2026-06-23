package com.microservices.payment.dto.response;

import com.microservices.payment.model.enums.*;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Value
@Builder
public class PaymentResponse {

    UUID id;
    String paymentReference;
    String transactionId;
    String sessionId;
    String externalReference;

    UUID userId;
    UUID orderId;
    UUID merchantId;

    BigDecimal amount;
    Currency currency;
    BigDecimal exchangeRate;
    BigDecimal amountInBaseCurrency;
    BigDecimal taxAmount;
    BigDecimal netAmount;
    BigDecimal feeAmount;

    PaymentMethod paymentMethod;
    PaymentGateway paymentGateway;
    PaymentRegion paymentRegion;
    PaymentStatus status;

    String maskedCardNumber;
    CardBrand cardBrand;
    String cardHolderName;
    String cardExpiry;

    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    LocalDateTime initiatedAt;
    LocalDateTime authorizedAt;
    LocalDateTime capturedAt;
    LocalDateTime completedAt;
    LocalDateTime cancelledAt;
    LocalDateTime expiresAt;

    BigDecimal refundAmount;
    String refundTransactionId;
    String refundReason;
    LocalDateTime refundedAt;

    String failureCode;
    String failureReason;
    Integer retryCount;

    String threeDsStatus;

    String description;
    String notes;
    String metadata;
}