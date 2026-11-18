package com.microservices.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class OrderServiceConfig {

    /** Header checked by order-service's InternalApiKeyFilter. */
    public static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    @Value("${order-service.base-url:http://localhost:8081/ecommerce-bj}")
    private String baseUrl;

    @Value("${order-service.internal-api-key:}")
    private String internalApiKey;

    @Bean("orderServiceRestClient")
    public RestClient orderServiceRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                // Service-to-service auth: lets payment-service call order-service's internal endpoints
                .defaultHeader(INTERNAL_API_KEY_HEADER, internalApiKey)
                .build();
    }
}
