package com.microservices.payment.gateway.culqi.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CulqiRefundRequest {
    String chargeId;
    Integer amount;
    /** Culqi accepted values: "solicitud_comprador", "falla_calidad", "no_reconocido", "duplicado", "fraude". */
    String reason;
}