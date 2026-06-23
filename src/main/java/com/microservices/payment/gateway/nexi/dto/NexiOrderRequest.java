package com.microservices.payment.gateway.nexi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NexiOrderRequest {

    NexiOrder order;
    NexiPaymentSession paymentSession;

    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NexiOrder {
        String orderId;
        String amount;
        String currency;
        String customerId;
        String description;
    }

    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NexiPaymentSession {
        String actionType;
        String amount;
        String currency;
        String language;
        String resultUrl;
        String cancelUrl;
        String notificationUrl;
    }
}