package com.microservices.payment.controller;

import com.microservices.payment.dto.request.CreatePaymentIntentRequest;
import com.microservices.payment.dto.response.CreatePaymentIntentResponse;
import com.microservices.payment.service.StripePaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments/intents")
@RequiredArgsConstructor
public class PaymentIntentController {

    private final StripePaymentService stripePaymentService;

    /**
     * Creates (or reuses) the Stripe PaymentIntent for an order and returns its clientSecret.
     * The amount is never taken from the request: it is read from order-service.
     */
    @PostMapping
    public ResponseEntity<CreatePaymentIntentResponse> createIntent(@Valid @RequestBody CreatePaymentIntentRequest request) {
        return ResponseEntity.ok(stripePaymentService.createIntent(request, resolveCallerSubject()));
    }

    /** Null for guest checkout (no Authorization header, or a token that did not validate). */
    private String resolveCallerSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getName();
    }
}
