package com.microservices.payment.gateway.nexi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NexiOrderResponse {
    private String hostedPage;     // presente con /orders/hpp
    private String sessionId;      // presente con /orders/2steps/init
    private String securityToken;
    private List<String> warnings;
}