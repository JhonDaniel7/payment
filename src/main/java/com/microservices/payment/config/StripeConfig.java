package com.microservices.payment.config;

import com.stripe.StripeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stripe server-side client.
 *
 * <p>Uses an injected {@link StripeClient} instead of the global {@code Stripe.apiKey}:
 * same effect, but no static mutable state and easy to mock in tests.
 * The secret key is read from {@code gateway.stripe.secret-key} (env var STRIPE_SECRET_KEY)
 * and never leaves the backend.
 */
@Slf4j
@Configuration
public class StripeConfig {

    @Value("${gateway.stripe.secret-key:}")
    private String secretKey;

    @Bean
    public StripeClient stripeClient() {
        if (secretKey == null || secretKey.isBlank()) {
            log.warn("gateway.stripe.secret-key is not set (STRIPE_SECRET_KEY) — Stripe calls will fail until it is configured");
        } else if (secretKey.startsWith("sk_live_")) {
            log.warn("Stripe is running with a LIVE secret key — real charges will be made");
        }
        return new StripeClient(secretKey == null ? "" : secretKey);
    }
}
