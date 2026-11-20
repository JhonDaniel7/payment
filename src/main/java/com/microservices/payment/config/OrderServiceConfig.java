package com.microservices.payment.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Slf4j
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
        if (internalApiKey == null || internalApiKey.isBlank()) {
            // e.g. INTERNAL_API_KEY defined but empty: every call to order-service would get 401
            log.error("order-service.internal-api-key is EMPTY — set INTERNAL_API_KEY (same value as order-service)");
        }
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
