package com.microservices.payment.controller;

import com.microservices.payment.dto.request.PaymentRequest;
import com.microservices.payment.dto.request.PaymentStatusUpdateRequest;
import com.microservices.payment.dto.request.RefundRequest;
import com.microservices.payment.dto.response.PaymentPageResponse;
import com.microservices.payment.dto.response.PaymentResponse;
import com.microservices.payment.model.enums.PaymentMethod;
import com.microservices.payment.model.enums.PaymentRegion;
import com.microservices.payment.model.enums.PaymentStatus;
import com.microservices.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    // ─────────────────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody PaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.createPayment(request));
    }

    // ─────────────────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<PaymentPageResponse> getAllPayments(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) PaymentRegion paymentRegion,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return ResponseEntity.ok(paymentService.getAll(userId, orderId, status, paymentRegion, paymentMethod, page, size, sort));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPaymentById(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentService.getById(id));
    }

    @GetMapping("/reference/{paymentReference}")
    public ResponseEntity<PaymentResponse> getPaymentByReference(@PathVariable String paymentReference) {
        return ResponseEntity.ok(paymentService.getByReference(paymentReference));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<PaymentPageResponse> getPaymentsByUser(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(paymentService.getByUser(userId, page, size));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<List<PaymentResponse>> getPaymentsByOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(paymentService.getByOrder(orderId));
    }

    // ─────────────────────────────────────────────────────────
    // STATUS UPDATE
    // ─────────────────────────────────────────────────────────

    @PatchMapping("/{id}/status")
    public ResponseEntity<PaymentResponse> updatePaymentStatus(
            @PathVariable UUID id,
            @Valid @RequestBody PaymentStatusUpdateRequest request) {
        return ResponseEntity.ok(paymentService.updateStatus(id, request));
    }

    // ─────────────────────────────────────────────────────────
    // REFUND
    // ─────────────────────────────────────────────────────────

    @PostMapping("/{id}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(
            @PathVariable UUID id,
            @Valid @RequestBody RefundRequest request) {
        return ResponseEntity.ok(paymentService.refund(id, request));
    }

    // ─────────────────────────────────────────────────────────
    // CANCEL
    // ─────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<PaymentResponse> cancelPayment(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentService.cancel(id));
    }
}