package com.microservices.payment.gateway.nexi.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class NexiRefundRequest {
    String amount;
    String currency;
    String description;
}