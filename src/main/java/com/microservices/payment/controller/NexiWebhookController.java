package com.microservices.payment.controller;

import com.microservices.payment.gateway.nexi.dto.NexiWebhookPayload;
import com.microservices.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks/nexi")
@RequiredArgsConstructor
public class NexiWebhookController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<Void> handleNexiNotification(@RequestBody NexiWebhookPayload payload) {
        log.debug("Nexi webhook received — orderId={}", payload.getOperation() != null ? payload.getOperation().getOrderId() : "null");
        paymentService.handleNexiWebhook(payload);
        return ResponseEntity.ok().build();
    }
}