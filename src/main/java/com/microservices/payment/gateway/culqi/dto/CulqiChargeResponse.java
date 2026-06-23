package com.microservices.payment.gateway.culqi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CulqiChargeResponse {

    /** "charge" on success, "error" on failure. */
    private String object;

    /** Charge ID (e.g. chr_live_xxxxx) — present on success. */
    private String id;

    private Integer amount;
    private String currencyCode;
    private String email;
    private String description;
    private CulqiOutcome outcome;

    /** Error fields — present when object == "error". */
    private String type;
    private String code;
    private String declineCode;
    private String merchantMessage;
    private String userMessage;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class CulqiOutcome {
        private String type;
        private String code;
        private String declineCode;
        private String merchantMessage;
        private String userMessage;
    }
}