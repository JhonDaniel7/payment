package com.microservices.payment.dto.request;

import com.microservices.payment.model.enums.*;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

@Value
@Builder
public class PaymentRequest {

    @NotNull
    UUID orderId;

    @NotNull
    UUID userId;

    UUID merchantId;

    @NotNull
    @DecimalMin(value = "0.01")
    BigDecimal amount;

    @NotNull
    Currency currency;

    @NotNull
    PaymentMethod paymentMethod;

    PaymentGateway paymentGateway;

    @NotNull
    PaymentRegion paymentRegion;

    @Size(min = 4, max = 4)
    String maskedCardNumber;

    CardBrand cardBrand;

    @Size(max = 150)
    String cardHolderName;

    @Pattern(regexp = "^(0[1-9]|1[0-2])/\\d{2}$")
    String cardExpiry;

    @Size(max = 500)
    String description;

    @Size(max = 255)
    String externalReference;

    String metadata;

    @Size(max = 45)
    String ipAddress;

    @Size(max = 500)
    String userAgent;

    @Size(max = 255)
    String deviceId;
}