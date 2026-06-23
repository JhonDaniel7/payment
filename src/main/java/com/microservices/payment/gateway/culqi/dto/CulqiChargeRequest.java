package com.microservices.payment.gateway.culqi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CulqiChargeRequest {
    Integer amount;
    String currencyCode;
    String email;
    String sourceId;
    boolean capture;
    String description;
    Integer installments;
}
