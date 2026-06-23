package com.microservices.payment.gateway.nexi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NexiWebhookPayload {

    private NexiOperation operation;
    private String securityToken;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NexiOperation {
        private String orderId;
        private String operationId;
        private String operationType;
        private String operationResult;
        private String operationTime;
        private String paymentMethod;
        private String paymentCircuit;
        private String paymentInstrumentInfo;
        private String operationAmount;
        private String operationCurrency;
        private NexiCustomerInfo customerInfo;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NexiCustomerInfo {
        private String cardHolderName;
        private String cardHolderEmail;
    }
}