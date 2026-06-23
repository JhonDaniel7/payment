package com.microservices.payment.service;

import com.microservices.payment.dto.request.PaymentRequest;
import com.microservices.payment.dto.request.PaymentStatusUpdateRequest;
import com.microservices.payment.dto.request.RefundRequest;
import com.microservices.payment.dto.response.PaymentPageResponse;
import com.microservices.payment.dto.response.PaymentResponse;
import com.microservices.payment.gateway.nexi.dto.NexiWebhookPayload;
import com.microservices.payment.model.enums.PaymentMethod;
import com.microservices.payment.model.enums.PaymentRegion;
import com.microservices.payment.model.enums.PaymentStatus;

import java.util.List;
import java.util.UUID;

public interface PaymentService {

    PaymentResponse createPayment(PaymentRequest request);

    PaymentResponse getById(UUID id);

    PaymentResponse getByReference(String paymentReference);

    PaymentPageResponse getAll(UUID userId, UUID orderId, PaymentStatus status,
                               PaymentRegion region, PaymentMethod method,
                               int page, int size, String sort);

    PaymentPageResponse getByUser(UUID userId, int page, int size);

    List<PaymentResponse> getByOrder(UUID orderId);

    PaymentResponse updateStatus(UUID id, PaymentStatusUpdateRequest request);

    PaymentResponse refund(UUID id, RefundRequest request);

    PaymentResponse cancel(UUID id);

    void handleNexiWebhook(NexiWebhookPayload payload);
}