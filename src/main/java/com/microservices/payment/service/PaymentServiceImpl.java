package com.microservices.payment.service;

import com.microservices.payment.client.order.OrderServiceClient;
import com.microservices.payment.dto.request.PaymentRequest;
import com.microservices.payment.dto.request.PaymentStatusUpdateRequest;
import com.microservices.payment.dto.request.RefundRequest;
import com.microservices.payment.dto.response.PaymentPageResponse;
import com.microservices.payment.dto.response.PaymentResponse;
import com.microservices.payment.exception.InvalidPaymentStateException;
import com.microservices.payment.exception.OrderNotFoundException;
import com.microservices.payment.exception.OrderStateConflictException;
import com.microservices.payment.exception.PaymentNotFoundException;
import com.microservices.payment.gateway.GatewayResponse;
import com.microservices.payment.gateway.PaymentGatewayPort;
import com.microservices.payment.gateway.nexi.dto.NexiWebhookPayload;
import com.microservices.payment.model.entity.Payment;
import com.microservices.payment.model.enums.PaymentGateway;
import com.microservices.payment.model.enums.PaymentMethod;
import com.microservices.payment.model.enums.PaymentRegion;
import com.microservices.payment.model.enums.PaymentStatus;
import com.microservices.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final Set<PaymentStatus> CANCELLABLE_STATUSES = Set.of(
            PaymentStatus.PENDING, PaymentStatus.INITIATED
    );

    private static final Set<PaymentStatus> REFUNDABLE_STATUSES = Set.of(
            PaymentStatus.COMPLETED, PaymentStatus.PARTIALLY_REFUNDED
    );

    private final PaymentRepository paymentRepository;
    private final List<PaymentGatewayPort> gateways;
    private final StripePaymentService stripePaymentService;
    private final OrderServiceClient orderServiceClient;

    // ─────────────────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentResponse createPayment(PaymentRequest request) {
        PaymentGatewayPort gateway = resolveGateway(request.getPaymentRegion(), request.getPaymentMethod());

        Payment payment = Payment.builder()
                .paymentReference(generateReference())
                .orderId(request.getOrderId())
                .userId(request.getUserId())
                .merchantId(request.getMerchantId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .paymentMethod(request.getPaymentMethod())
                .paymentGateway(request.getPaymentGateway())
                .paymentRegion(request.getPaymentRegion())
                .maskedCardNumber(request.getMaskedCardNumber())
                .cardBrand(request.getCardBrand())
                .cardHolderName(request.getCardHolderName())
                .cardExpiry(request.getCardExpiry())
                .description(request.getDescription())
                .externalReference(request.getExternalReference())
                .metadata(request.getMetadata())
                .ipAddress(request.getIpAddress())
                .userAgent(request.getUserAgent())
                .deviceId(request.getDeviceId())
                .status(PaymentStatus.PENDING)
                .retryCount(0)
                .build();

        payment.setInitiatedAt(LocalDateTime.now());
        payment = paymentRepository.save(payment);

        log.info("Payment created — ref={} gateway={}", payment.getPaymentReference(), gateway.getClass().getSimpleName());

        GatewayResponse gatewayResponse = gateway.charge(request);
        applyGatewayResponse(payment, gatewayResponse);
        notifyOrderIfPaid(payment);

        return toResponse(paymentRepository.save(payment));
    }

    // ─────────────────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getById(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getByReference(String paymentReference) {
        return paymentRepository.findByPaymentReference(paymentReference)
                .map(this::toResponse)
                .orElseThrow(() -> new PaymentNotFoundException("Payment reference not found: " + paymentReference));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentPageResponse getAll(UUID userId, UUID orderId, PaymentStatus status,
                                      PaymentRegion region, PaymentMethod method,
                                      int page, int size, String sort) {
        Specification<Payment> spec = (root, query, cb) -> cb.conjunction();

        if (userId != null)  spec = spec.and((r, q, cb) -> cb.equal(r.get("userId"), userId));
        if (orderId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("orderId"), orderId));
        if (status != null)  spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), status));
        if (region != null)  spec = spec.and((r, q, cb) -> cb.equal(r.get("paymentRegion"), region));
        if (method != null)  spec = spec.and((r, q, cb) -> cb.equal(r.get("paymentMethod"), method));

        Page<Payment> result = paymentRepository.findAll(spec, toPageable(page, size, sort));
        return toPageResponse(result);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentPageResponse getByUser(UUID userId, int page, int size) {
        Page<Payment> result = paymentRepository.findByUserId(userId, toPageable(page, size, "createdAt,desc"));
        return toPageResponse(result);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentResponse> getByOrder(UUID orderId) {
        return paymentRepository.findByOrderId(orderId).stream()
                .map(this::toResponse)
                .toList();
    }

    // ─────────────────────────────────────────────────────────
    // STATUS UPDATE
    // ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentResponse updateStatus(UUID id, PaymentStatusUpdateRequest request) {
        Payment payment = findOrThrow(id);

        payment.setStatus(request.getStatus());
        applyStatusTimestamp(payment, request.getStatus());

        if (request.getTransactionId() != null) payment.setTransactionId(request.getTransactionId());
        if (request.getFailureCode() != null)   payment.setFailureCode(request.getFailureCode());
        if (request.getFailureReason() != null) payment.setFailureReason(request.getFailureReason());
        if (request.getNotes() != null)         payment.setNotes(request.getNotes());

        log.info("Payment status updated — id={} status={}", id, request.getStatus());
        return toResponse(paymentRepository.save(payment));
    }

    // ─────────────────────────────────────────────────────────
    // REFUND
    // ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentResponse refund(UUID id, RefundRequest request) {
        Payment payment = findOrThrow(id);

        if (!REFUNDABLE_STATUSES.contains(payment.getStatus())) {
            throw new InvalidPaymentStateException(
                    "Cannot refund payment in status: " + payment.getStatus());
        }

        BigDecimal alreadyRefunded = payment.getRefundAmount() != null ? payment.getRefundAmount() : BigDecimal.ZERO;
        BigDecimal newTotal = alreadyRefunded.add(request.getRefundAmount());

        if (newTotal.compareTo(payment.getAmount()) > 0) {
            throw new InvalidPaymentStateException(
                    "Refund amount exceeds original payment amount");
        }

        // Stripe payments are refunded on Stripe (region/method routing would pick another gateway).
        // The outcome is recorded asynchronously by the charge.refunded webhook.
        if (payment.getPaymentGateway() == PaymentGateway.STRIPE) {
            return toResponse(stripePaymentService.requestRefund(payment, request.getRefundAmount(), request.getRefundReason()));
        }

        PaymentGatewayPort gateway = resolveGateway(payment.getPaymentRegion(), payment.getPaymentMethod());
        GatewayResponse gatewayResponse = gateway.refund(payment.getTransactionId(), request.getRefundAmount(), request.getRefundReason());

        payment.setRefundAmount(newTotal);
        payment.setRefundReason(request.getRefundReason());
        payment.setRefundedAt(LocalDateTime.now());
        payment.setRefundTransactionId(gatewayResponse.getTransactionId());
        payment.setStatus(newTotal.compareTo(payment.getAmount()) == 0
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED);

        log.info("Payment refunded — id={} amount={} status={}", id, newTotal, payment.getStatus());
        return toResponse(paymentRepository.save(payment));
    }

    // ─────────────────────────────────────────────────────────
    // CANCEL
    // ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PaymentResponse cancel(UUID id) {
        Payment payment = findOrThrow(id);

        if (!CANCELLABLE_STATUSES.contains(payment.getStatus())) {
            throw new InvalidPaymentStateException(
                    "Cannot cancel payment in status: " + payment.getStatus());
        }

        payment.setStatus(PaymentStatus.CANCELLED);
        payment.setCancelledAt(LocalDateTime.now());

        log.info("Payment cancelled — id={}", id);
        return toResponse(paymentRepository.save(payment));
    }

    // ─────────────────────────────────────────────────────────
    // NEXI WEBHOOK
    // ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void handleNexiWebhook(NexiWebhookPayload payload) {
        NexiWebhookPayload.NexiOperation op = payload.getOperation();
        log.info("Nexi webhook received — orderId={} operationResult={}", op.getOrderId(), op.getOperationResult());

        // Look up by the securityToken we stored in transactionId at charge time
        Payment payment = paymentRepository.findByTransactionId(payload.getSecurityToken())
                .orElseThrow(() -> new PaymentNotFoundException(
                        "No payment found for Nexi securityToken: " + payload.getSecurityToken()));

        PaymentStatus newStatus = mapNexiResult(op.getOperationResult());

        // Replace temporary securityToken with the real Nexi operationId
        payment.setTransactionId(op.getOperationId());
        payment.setStatus(newStatus);
        applyStatusTimestamp(payment, newStatus);

        if (op.getCustomerInfo() != null) {
            payment.setCardHolderName(op.getCustomerInfo().getCardHolderName());
        }
        if (op.getPaymentInstrumentInfo() != null) {
            String last4 = op.getPaymentInstrumentInfo().replaceAll("[^0-9]", "");
            if (last4.length() >= 4) {
                payment.setMaskedCardNumber(last4.substring(last4.length() - 4));
            }
        }

        notifyOrderIfPaid(payment);
        paymentRepository.save(payment);
        log.info("Payment updated from Nexi webhook — id={} status={}", payment.getId(), newStatus);
    }

    /**
     * Culqi / Nexi: a COMPLETED payment moves the order to PAID (order-service is idempotent, so a
     * re-delivered webhook is harmless). A failed attempt does NOT cancel the order: the customer
     * can retry with a new payment. Never throws — the money is already collected, so the payment
     * must be saved anyway; a failed sync is logged and written in the notes.
     */
    private void notifyOrderIfPaid(Payment payment) {
        if (payment.getStatus() != PaymentStatus.COMPLETED || payment.getOrderId() == null) return;
        try {
            orderServiceClient.updateOrderPayment(payment.getOrderId(), payment.getId(), "PAID");
        } catch (OrderStateConflictException | OrderNotFoundException ex) {
            log.error("MANUAL ACTION — payment {} completed but order {} cannot be paid: {}",
                    payment.getId(), payment.getOrderId(), ex.getMessage());
            payment.setNotes(truncate("MANUAL ACTION: order not payable, refund the customer — " + ex.getMessage()));
        } catch (RuntimeException ex) {
            log.error("Order {} NOT updated to PAID for payment {}: {}",
                    payment.getOrderId(), payment.getId(), ex.getMessage(), ex);
            payment.setNotes(truncate("Order not updated to PAID: " + ex.getMessage()));
        }
    }

    private static String truncate(String value) {
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private PaymentStatus mapNexiResult(String operationResult) {
        if (operationResult == null) return PaymentStatus.PENDING;
        return switch (operationResult.toUpperCase()) {
            case "AUTHORIZED"                          -> PaymentStatus.AUTHORIZED;
            case "EXECUTED"                            -> PaymentStatus.COMPLETED;
            case "THREEDS_VALIDATED"                   -> PaymentStatus.REQUIRES_ACTION;
            case "PENDING"                             -> PaymentStatus.PENDING;
            case "CANCELED", "VOIDED", "REVERSED"     -> PaymentStatus.CANCELLED;
            case "REFUNDED"                            -> PaymentStatus.REFUNDED;
            default                                    -> PaymentStatus.FAILED;
        };
    }

    // ─────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────

    private Payment findOrThrow(UUID id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + id));
    }

    private PaymentGatewayPort resolveGateway(PaymentRegion region, PaymentMethod method) {
        return gateways.stream()
                .filter(g -> g.supports(region, method))
                .findFirst()
                .orElseThrow(() -> new InvalidPaymentStateException(
                        "No gateway available for region=" + region + " method=" + method));
    }

    private void applyGatewayResponse(Payment payment, GatewayResponse response) {
        payment.setStatus(response.getStatus());
        payment.setTransactionId(response.getTransactionId());
        payment.setSessionId(response.getSessionId());
        payment.setFailureCode(response.getFailureCode());
        payment.setFailureReason(response.getFailureReason());
        payment.setGatewayErrorMessage(response.getGatewayErrorMessage());
        payment.setThreeDsStatus(response.getThreeDsStatus());
        payment.setThreeDsTransactionId(response.getThreeDsTransactionId());
        applyStatusTimestamp(payment, response.getStatus());
    }

    private void applyStatusTimestamp(Payment payment, PaymentStatus status) {
        LocalDateTime now = LocalDateTime.now();
        switch (status) {
            case INITIATED              -> payment.setInitiatedAt(now);
            case AUTHORIZED             -> payment.setAuthorizedAt(now);
            case CAPTURED               -> payment.setCapturedAt(now);
            case COMPLETED              -> payment.setCompletedAt(now);
            case CANCELLED              -> payment.setCancelledAt(now);
            case REFUNDED,
                 PARTIALLY_REFUNDED     -> payment.setRefundedAt(now);
            default                     -> { }
        }
    }

    private String generateReference() {
        String date   = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "PAY-" + date + "-" + random;
    }

    private Pageable toPageable(int page, int size, String sort) {
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(page, size, Sort.by(dir, field));
    }

    private PaymentPageResponse toPageResponse(Page<Payment> pageResult) {
        return PaymentPageResponse.builder()
                .content(pageResult.getContent().stream().map(this::toResponse).toList())
                .totalElements(pageResult.getTotalElements())
                .totalPages(pageResult.getTotalPages())
                .currentPage(pageResult.getNumber())
                .pageSize(pageResult.getSize())
                .hasNext(pageResult.hasNext())
                .hasPrevious(pageResult.hasPrevious())
                .build();
    }

    private PaymentResponse toResponse(Payment p) {
        return PaymentResponse.builder()
                .id(p.getId())
                .paymentReference(p.getPaymentReference())
                .transactionId(p.getTransactionId())
                .sessionId(p.getSessionId())
                .externalReference(p.getExternalReference())
                .userId(p.getUserId())
                .customerId(p.getCustomerId())
                .orderId(p.getOrderId())
                .merchantId(p.getMerchantId())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .exchangeRate(p.getExchangeRate())
                .amountInBaseCurrency(p.getAmountInBaseCurrency())
                .taxAmount(p.getTaxAmount())
                .netAmount(p.getNetAmount())
                .feeAmount(p.getFeeAmount())
                .paymentMethod(p.getPaymentMethod())
                .paymentGateway(p.getPaymentGateway())
                .paymentRegion(p.getPaymentRegion())
                .status(p.getStatus())
                .maskedCardNumber(p.getMaskedCardNumber())
                .cardBrand(p.getCardBrand())
                .cardHolderName(p.getCardHolderName())
                .cardExpiry(p.getCardExpiry())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .initiatedAt(p.getInitiatedAt())
                .authorizedAt(p.getAuthorizedAt())
                .capturedAt(p.getCapturedAt())
                .completedAt(p.getCompletedAt())
                .cancelledAt(p.getCancelledAt())
                .expiresAt(p.getExpiresAt())
                .refundAmount(p.getRefundAmount())
                .refundTransactionId(p.getRefundTransactionId())
                .refundReason(p.getRefundReason())
                .refundedAt(p.getRefundedAt())
                .failureCode(p.getFailureCode())
                .failureReason(p.getFailureReason())
                .retryCount(p.getRetryCount())
                .threeDsStatus(p.getThreeDsStatus())
                .description(p.getDescription())
                .notes(p.getNotes())
                .metadata(p.getMetadata())
                .build();
    }
}