package com.microservices.payment.controller;

import com.microservices.payment.service.StripePaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint called by Stripe. Authenticated by the Stripe-Signature header, not by a JWT.
 */
@RestController
@RequestMapping("/api/v1/webhooks/stripe")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final StripePaymentService stripePaymentService;

    /**
     * The body must be read as the raw String: the signature is computed over the exact bytes,
     * so binding it to a DTO and re-serializing would break the verification.
     */
    @PostMapping
    public ResponseEntity<Void> handle(@RequestBody String payload,
                                       @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        stripePaymentService.handleWebhook(payload, signature);
        return ResponseEntity.ok().build();
    }
}
