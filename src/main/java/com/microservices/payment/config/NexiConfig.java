package com.microservices.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class NexiConfig {

    @Value("${gateway.nexi.base-url:https://int-ecommerce.nexi.it/ecomm/api/phoenix-0.0/psp/api/v1}")
    private String baseUrl;

    @Value("${gateway.nexi.api-key:}")
    private String apiKey;

    @Bean("nexiRestClient")
    public RestClient nexiRestClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Api-Key", apiKey)
                .build();
    }
}