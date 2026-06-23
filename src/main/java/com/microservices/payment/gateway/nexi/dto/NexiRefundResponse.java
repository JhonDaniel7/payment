package com.microservices.payment.gateway.nexi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NexiRefundResponse {
    private String operationId;
    private String operationTime;
    private List<String> warnings;
}