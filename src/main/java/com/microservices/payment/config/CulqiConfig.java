package com.microservices.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class CulqiConfig {

    @Value("${gateway.culqi.base-url:https://api.culqi.com/v2}")
    private String baseUrl;

    @Value("${gateway.culqi.api-key:}")
    private String apiKey;

    @Bean("culqiRestClient")
    public RestClient culqiRestClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }
}