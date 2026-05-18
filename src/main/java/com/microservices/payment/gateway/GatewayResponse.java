package com.microservices.payment.gateway;

import com.microservices.payment.model.enums.PaymentStatus;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class GatewayResponse {

    boolean success;

    PaymentStatus status;

    String transactionId;

    String failureCode;

    String failureReason;

    String gatewayErrorMessage;

    String threeDsStatus;

    String threeDsTransactionId;

    /** True when the gateway requires a 3DS redirect before completing (EU/PSD2). */
    boolean requiresAction;

    /** Redirect URL for 3DS authentication — populated only when requiresAction is true. */
    String actionUrl;
}