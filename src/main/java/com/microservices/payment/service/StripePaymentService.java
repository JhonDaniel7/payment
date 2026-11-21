package com.microservices.payment.service;

import com.microservices.payment.client.order.OrderServiceClient;
import com.microservices.payment.client.order.OrderSnapshot;
import com.microservices.payment.dto.request.CreatePaymentIntentRequest;
import com.microservices.payment.dto.response.CreatePaymentIntentResponse;
import com.microservices.payment.exception.GatewayException;
import com.microservices.payment.exception.InvalidWebhookSignatureException;
import com.microservices.payment.exception.OrderNotFoundException;
import com.microservices.payment.exception.OrderStateConflictException;
import com.microservices.payment.model.entity.Payment;
import com.microservices.payment.model.enums.Currency;
import com.microservices.payment.model.enums.PaymentGateway;
import com.microservices.payment.model.enums.PaymentMethod;
import com.microservices.payment.model.enums.PaymentRegion;
import com.microservices.payment.model.enums.PaymentStatus;
import com.microservices.payment.repository.PaymentRepository;
import com.stripe.StripeClient;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.StripeCollection;
import com.stripe.model.StripeError;
import com.stripe.model.StripeObject;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentUpdateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stripe card payments with PaymentIntents.
 *
 * <ol>
 *   <li>{@link #createIntent}: reads the order from order-service (the only source of the amount),
 *       creates — or reuses — a PaymentIntent and returns its clientSecret to the frontend.</li>
 *   <li>The frontend confirms with Stripe.js (card data never touches our servers).</li>
 *   <li>{@link #handleWebhook}: Stripe reports the final outcome (also after 3D Secure);
 *       on success the order is marked PAID in order-service.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripePaymentService {

    /** PaymentIntent statuses in which the same intent can still be confirmed by the customer. */
    private static final Set<String> CONFIRMABLE_INTENT_STATUSES = Set.of(
            "requires_payment_method", "requires_confirmation", "requires_action"
    );

    /** PaymentIntent statuses meaning money is (being) collected — never open a second intent. */
    private static final Set<String> IN_FLIGHT_INTENT_STATUSES = Set.of(
            "processing", "requires_capture", "succeeded"
    );

    /** Our statuses meaning the payment went through — later failure/cancel events must not downgrade it. */
    private static final Set<PaymentStatus> SETTLED_STATUSES = Set.of(
            PaymentStatus.COMPLETED, PaymentStatus.REFUND_PENDING,
            PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED
    );

    /** Refund statuses meaning the money is (going) back to the customer. */
    private static final Set<String> ACTIVE_REFUND_STATUSES = Set.of("pending", "requires_action", "succeeded");

    /** Refund metadata key carrying the RMA number of the order-service return the refund pays out. */
    static final String RETURN_REFERENCE_METADATA = "returnReference";

    /** Refund statuses meaning the money came back to us: the customer was NOT refunded. */
    private static final Set<String> FAILED_REFUND_STATUSES = Set.of("failed", "canceled");

    private static final Set<String> SEPA_COUNTRIES = Set.of(
            "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GR", "HR", "HU", "IE",
            "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK"
    );

    private final StripeClient stripe;
    private final OrderServiceClient orderServiceClient;
    private final PaymentRepository paymentRepository;

    @Value("${gateway.stripe.secret-key:}")
    private String secretKey;

    @Value("${gateway.stripe.webhook-secret:}")
    private String webhookSecret;

    // ─────────────────────────────────────────────────────────
    // CREATE / REUSE PAYMENT INTENT
    // ─────────────────────────────────────────────────────────

    /**
     * @param callerSubject JWT subject of the caller, or null for guest checkout
     */
    public CreatePaymentIntentResponse createIntent(CreatePaymentIntentRequest request, String callerSubject) {
        if (request.provider() != PaymentGateway.STRIPE) {
            throw new IllegalArgumentException("Unsupported provider: " + request.provider() + " (only STRIPE)");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new GatewayException("Stripe is not configured: set STRIPE_SECRET_KEY");
        }

        UUID orderId = request.orderId();
        OrderSnapshot order = orderServiceClient.getOrder(orderId);

        // An order placed by a logged-in customer can only be paid by that customer.
        // Answer 404 rather than 403 so order ids cannot be probed.
        if (order.customerId() != null && !order.customerId().equals(callerSubject)) {
            log.warn("Payment intent refused — order {} belongs to another customer (caller={})", orderId, callerSubject);
            throw new OrderNotFoundException(orderId);
        }
        if (!OrderSnapshot.STATUS_PENDING_PAYMENT.equals(order.status())) {
            throw new OrderStateConflictException(
                    "Order " + orderId + " is " + order.status() + ", expected " + OrderSnapshot.STATUS_PENDING_PAYMENT);
        }

        Currency currency = parseCurrency(order);
        BigDecimal amount = parseAmount(order);
        long amountMinor = amount.movePointRight(2).longValueExact();
        String stripeCurrency = currency.name().toLowerCase(Locale.ROOT);

        Optional<CreatePaymentIntentResponse> reused = paymentRepository
                .findFirstByOrderIdAndPaymentGatewayOrderByCreatedAtDesc(orderId, PaymentGateway.STRIPE)
                .flatMap(previous -> tryReuse(previous, amount, amountMinor, stripeCurrency));
        if (reused.isPresent()) {
            return reused.get();
        }

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountMinor)
                .setCurrency(stripeCurrency)
                // The frontend uses the Card Element → card only.
                .addAllowedPaymentMethodType(PaymentIntentCreateParams.AllowedPaymentMethodType.CARD)
                .setDescription("Order " + orderId)
                .putMetadata("orderId", orderId.toString())
                .build();

        // Same key → Stripe returns the same intent instead of creating a duplicate (double click,
        // client retry). The attempt counter gives a fresh key once a previous intent was cancelled.
        long attempt = paymentRepository.countByOrderIdAndPaymentGateway(orderId, PaymentGateway.STRIPE);
        String idempotencyKey = "pi-create-" + orderId + "-" + amountMinor + stripeCurrency + "-" + attempt;
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();

        PaymentIntent intent = stripeCall("create PaymentIntent",
                () -> stripe.v1().paymentIntents().create(params, options));

        Payment payment = paymentRepository.findByTransactionId(intent.getId())
                .orElseGet(() -> paymentRepository.save(Payment.builder()
                        .paymentReference(generateReference())
                        .transactionId(intent.getId())
                        .orderId(orderId)
                        .customerId(order.customerId())
                        .amount(amount)
                        .currency(currency)
                        .paymentMethod(PaymentMethod.CREDIT_CARD)
                        .paymentGateway(PaymentGateway.STRIPE)
                        .paymentRegion(resolveRegion(order))
                        .status(PaymentStatus.PENDING)
                        .initiatedAt(LocalDateTime.now())
                        .description("Order " + orderId)
                        .retryCount(0)
                        .build()));

        log.info("Stripe PaymentIntent created — paymentId={} intent={} orderId={} amount={} {}",
                payment.getId(), intent.getId(), orderId, amount, currency);
        return new CreatePaymentIntentResponse(payment.getId(), PaymentGateway.STRIPE, intent.getClientSecret());
    }

    /**
     * Returns the previous intent for this order if the customer can still confirm it, so retries
     * (page reload, declined card) never create a second chargeable intent.
     */
    private Optional<CreatePaymentIntentResponse> tryReuse(Payment previous, BigDecimal amount,
                                                           long amountMinor, String stripeCurrency) {
        if (SETTLED_STATUSES.contains(previous.getStatus()) || previous.getStatus() == PaymentStatus.PROCESSING) {
            throw new OrderStateConflictException(
                    "Order " + previous.getOrderId() + " already has a payment in status " + previous.getStatus());
        }
        if (previous.getStatus() == PaymentStatus.CANCELLED || previous.getTransactionId() == null) {
            return Optional.empty();
        }

        PaymentIntent intent = stripeCall("retrieve PaymentIntent",
                () -> stripe.v1().paymentIntents().retrieve(previous.getTransactionId()));

        if (IN_FLIGHT_INTENT_STATUSES.contains(intent.getStatus())) {
            // The webhook has not arrived yet: the order will be finalized asynchronously.
            throw new OrderStateConflictException(
                    "Order " + previous.getOrderId() + " already has a payment " + intent.getStatus());
        }
        if (!CONFIRMABLE_INTENT_STATUSES.contains(intent.getStatus())) {
            markCancelled(previous);
            return Optional.empty();
        }

        if (!stripeCurrency.equals(intent.getCurrency())) {
            // Currency changed (shipping country edited): an intent's currency is fixed, start over.
            // "duplicate" = replaced by a new intent: the payment_intent.canceled webhook must NOT cancel the order
            PaymentIntentCancelParams cancel = PaymentIntentCancelParams.builder()
                    .setCancellationReason(PaymentIntentCancelParams.CancellationReason.DUPLICATE)
                    .build();
            stripeCall("cancel PaymentIntent", () -> stripe.v1().paymentIntents().cancel(intent.getId(), cancel));
            markCancelled(previous);
            return Optional.empty();
        }

        PaymentIntent current = intent;
        if (intent.getAmount() == null || intent.getAmount() != amountMinor) {
            // Order total changed since the intent was created: keep the intent, fix the amount.
            PaymentIntentUpdateParams update = PaymentIntentUpdateParams.builder().setAmount(amountMinor).build();
            current = stripeCall("update PaymentIntent amount",
                    () -> stripe.v1().paymentIntents().update(intent.getId(), update));
            previous.setAmount(amount);
            paymentRepository.save(previous);
        }

        log.info("Stripe PaymentIntent reused — paymentId={} intent={} status={}",
                previous.getId(), current.getId(), current.getStatus());
        return Optional.of(new CreatePaymentIntentResponse(
                previous.getId(), PaymentGateway.STRIPE, current.getClientSecret()));
    }

    // ─────────────────────────────────────────────────────────
    // WEBHOOK
    // ─────────────────────────────────────────────────────────

    /**
     * Verifies the Stripe-Signature and applies the event. Stripe retries on any non-2xx answer and may
     * deliver an event more than once or out of order, so every handler is idempotent and never
     * downgrades a settled payment.
     */
    @Transactional
    public void handleWebhook(String payload, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new GatewayException("Stripe webhook is not configured: set STRIPE_WEBHOOK_SECRET");
        }
        Event event;
        try {
            if (signatureHeader == null || signatureHeader.isBlank()) {
                throw new SignatureVerificationException("Missing Stripe-Signature header", null);
            }
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException ex) {
            throw new InvalidWebhookSignatureException("Invalid Stripe webhook signature", ex);
        }

        log.info("Stripe webhook — id={} type={}", event.getId(), event.getType());

        switch (event.getType()) {
            case "payment_intent.succeeded"      -> onSucceeded(paymentIntentOf(event));
            case "payment_intent.payment_failed" -> onFailed(paymentIntentOf(event));
            case "payment_intent.processing"     -> onProgress(paymentIntentOf(event), PaymentStatus.PROCESSING);
            case "payment_intent.requires_action" -> onProgress(paymentIntentOf(event), PaymentStatus.REQUIRES_ACTION);
            case "payment_intent.canceled"       -> onCanceled(paymentIntentOf(event));
            case "charge.refunded"               -> onChargeRefunded(dataObjectOf(event, Charge.class));
            case "refund.failed", "refund.updated" -> onRefundFailedOrUpdated(dataObjectOf(event, Refund.class));
            default                              -> log.debug("Stripe event {} ignored", event.getType());
        }
    }

    private void onSucceeded(PaymentIntent intent) {
        Optional<Payment> found = findPayment(intent);
        if (found.isEmpty()) return;
        Payment payment = found.get();

        if (payment.getStatus() != PaymentStatus.COMPLETED && !SETTLED_STATUSES.contains(payment.getStatus())) {
            LocalDateTime now = LocalDateTime.now();
            payment.setStatus(PaymentStatus.COMPLETED);
            payment.setAuthorizedAt(now);
            payment.setCapturedAt(now);
            payment.setCompletedAt(now);
            payment.setFailureCode(null);
            payment.setFailureReason(null);
            if (intent.getAmountReceived() != null) {
                payment.setAmount(BigDecimal.valueOf(intent.getAmountReceived()).movePointLeft(2));
            }
            paymentRepository.save(payment);
            log.info("Stripe payment completed — paymentId={} intent={}", payment.getId(), intent.getId());
        }

        // Always (re)notify: order-service is idempotent, and this also repairs a previous delivery
        // whose order-service call failed. A transport error propagates → non-2xx → Stripe retries.
        try {
            orderServiceClient.updateOrderPayment(payment.getOrderId(), payment.getId(), "PAID");
        } catch (OrderStateConflictException | OrderNotFoundException ex) {
            // Money was collected but the order can no longer be paid (cancelled meanwhile, already paid
            // by another payment, or gone): give the money back.
            autoRefund(payment, ex.getMessage());
        }
    }

    /**
     * Full refund of a payment whose order cannot accept it. Runs at most once per payment: the
     * idempotency key is fixed and a payment that already has a refund is skipped, so a re-delivered
     * {@code payment_intent.succeeded} never refunds twice. If Stripe refuses, the error propagates →
     * non-2xx → Stripe re-delivers the event and the refund is retried. The final amounts are then
     * recorded, as for every refund, by the {@code charge.refunded} webhook.
     */
    private void autoRefund(Payment payment, String why) {
        if (payment.getRefundTransactionId() != null
                || payment.getStatus() == PaymentStatus.REFUND_PENDING
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                || payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Automatic refund already issued — paymentId={} refund={}", payment.getId(), payment.getRefundTransactionId());
            return;
        }
        log.warn("Payment {} succeeded but order {} cannot accept it ({}) — refunding automatically",
                payment.getId(), payment.getOrderId(), why);

        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(payment.getTransactionId()) // no amount = full refund
                .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                .putMetadata("paymentId", payment.getId().toString())
                .putMetadata("orderId", payment.getOrderId().toString())
                .putMetadata("autoRefund", "true")
                .putMetadata("reason", "Order not payable")
                .build();
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("auto-refund-" + payment.getId())
                .build();

        Refund refund = stripeCall("create automatic Refund", () -> stripe.v1().refunds().create(params, options));

        String reason = truncate("Automatic refund — order not payable: " + why, 500);
        payment.setStatus(PaymentStatus.REFUND_PENDING);
        payment.setRefundReason(reason);
        payment.setRefundTransactionId(refund.getId());
        payment.setNotes(truncate(reason + " (refund " + refund.getId() + ")", 1000));
        paymentRepository.save(payment);
        log.info("Automatic refund issued — paymentId={} refund={} status={}", payment.getId(), refund.getId(), refund.getStatus());
    }

    /**
     * The order is NOT cancelled: with PaymentIntents the customer can retry on the same intent
     * (e.g. another card), which is then reused by {@link #createIntent}.
     */
    private void onFailed(PaymentIntent intent) {
        Optional<Payment> found = findPayment(intent);
        if (found.isEmpty()) return;
        Payment payment = found.get();
        if (SETTLED_STATUSES.contains(payment.getStatus())) return;

        StripeError error = intent.getLastPaymentError();
        boolean cardError = error != null && "card_error".equals(error.getType());
        payment.setStatus(cardError ? PaymentStatus.DECLINED : PaymentStatus.FAILED);
        if (error != null) {
            payment.setFailureCode(truncate(error.getDeclineCode() != null ? error.getDeclineCode() : error.getCode(), 100));
            payment.setFailureReason(truncate(error.getMessage(), 500));
        }
        payment.setRetryCount(payment.getRetryCount() == null ? 1 : payment.getRetryCount() + 1);
        paymentRepository.save(payment);
        log.info("Stripe payment failed — paymentId={} status={} code={}",
                payment.getId(), payment.getStatus(), payment.getFailureCode());
    }

    private void onProgress(PaymentIntent intent, PaymentStatus status) {
        findPayment(intent)
                .filter(p -> !SETTLED_STATUSES.contains(p.getStatus()))
                .ifPresent(p -> {
                    p.setStatus(status);
                    paymentRepository.save(p);
                    log.info("Stripe payment {} — paymentId={}", status, p.getId());
                });
    }

    /**
     * A cancelled intent can no longer be paid — the definitive failure of the checkout — so the order
     * is cancelled too, except when this service replaced the intent with a new one (reason
     * "duplicate") or another payment of the same order is still alive. A declined card
     * ({@code payment_failed}) is not definitive: the customer can retry, the order stays PENDING_PAYMENT.
     */
    private void onCanceled(PaymentIntent intent) {
        Optional<Payment> found = findPayment(intent)
                .filter(p -> !SETTLED_STATUSES.contains(p.getStatus()));
        if (found.isEmpty()) return;
        Payment payment = found.get();
        markCancelled(payment);

        if ("duplicate".equals(intent.getCancellationReason())) {
            log.info("Stripe intent {} replaced by a new one — order {} left open", intent.getId(), payment.getOrderId());
            return;
        }
        boolean anotherPaymentAlive = paymentRepository.findByOrderId(payment.getOrderId()).stream()
                .anyMatch(p -> !p.getId().equals(payment.getId()) && p.getStatus() != PaymentStatus.CANCELLED
                        && p.getStatus() != PaymentStatus.FAILED && p.getStatus() != PaymentStatus.DECLINED);
        if (anotherPaymentAlive) {
            log.info("Order {} has another active payment — not cancelled", payment.getOrderId());
            return;
        }
        // A transport error propagates → non-2xx → Stripe retries the event.
        try {
            orderServiceClient.updateOrderPayment(payment.getOrderId(), payment.getId(), "CANCELLED");
        } catch (OrderStateConflictException | OrderNotFoundException ex) {
            // Already PAID / CANCELLED / gone: nothing left to cancel.
            log.info("Order {} not cancelled: {}", payment.getOrderId(), ex.getMessage());
        }
    }

    /** A refund was created on Stripe — through our API or directly from the Stripe dashboard. */
    private void onChargeRefunded(Charge charge) {
        if (charge.getPaymentIntent() == null) {
            log.warn("Stripe charge.refunded without payment_intent (charge {}) — ignored", charge.getId());
            return;
        }
        findPaymentByIntent(charge.getPaymentIntent()).ifPresent(this::syncRefunds);
    }

    /**
     * {@code refund.failed}, or {@code refund.updated} to failed/canceled (a refund cancelled from the
     * dashboard is a failure too): the money went back to OUR Stripe balance and the customer got nothing.
     */
    private void onRefundFailedOrUpdated(Refund refund) {
        if (!FAILED_REFUND_STATUSES.contains(refund.getStatus())) {
            return; // refund.updated for metadata, ARN, etc.
        }
        if (refund.getPaymentIntent() == null) {
            log.warn("Stripe refund {} without payment_intent — ignored", refund.getId());
            return;
        }
        findPaymentByIntent(refund.getPaymentIntent()).ifPresent(payment -> {
            BigDecimal amount = refund.getAmount() == null ? null : BigDecimal.valueOf(refund.getAmount()).movePointLeft(2);
            log.error("MANUAL ACTION REQUIRED — refund {} of {} on payment {} {} ({}): the customer did NOT receive "
                            + "the money, refund them another way",
                    refund.getId(), amount, payment.getId(), refund.getStatus(), refund.getFailureReason());
            syncRefunds(payment);
            payment.setNotes(truncate("Refund " + refund.getId() + " of " + amount + " " + refund.getStatus()
                    + " (" + refund.getFailureReason() + ") — customer not refunded, refund them another way", 1000));
            paymentRepository.save(payment);
        });
    }

    /**
     * Rebuilds the refund state from Stripe's list of refunds of this payment — the source of truth —
     * instead of trusting deltas. Every refund is sent to order-service under its own id
     * ({@code stripe-refund:<id>}), and every failed one as its cancellation
     * ({@code stripe-refund-failed:<id>}): order-service ignores references it already has, so any
     * number of re-deliveries, in any order, converges to the same state.
     */
    private void syncRefunds(Payment payment) {
        List<Refund> refunds = listRefunds(payment.getTransactionId()); // newest first

        BigDecimal activeTotal = refunds.stream()
                .filter(r -> ACTIVE_REFUND_STATUSES.contains(r.getStatus()))
                .map(r -> BigDecimal.valueOf(r.getAmount()).movePointLeft(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal previous = payment.getRefundAmount() != null ? payment.getRefundAmount() : BigDecimal.ZERO;

        payment.setRefundAmount(activeTotal);
        if (activeTotal.compareTo(previous) > 0) {
            payment.setRefundedAt(LocalDateTime.now());
        }
        if (activeTotal.signum() == 0) {
            // every refund failed: the payment is simply completed again (and refundable again)
            if (SETTLED_STATUSES.contains(payment.getStatus())) {
                payment.setStatus(PaymentStatus.COMPLETED);
            }
        } else {
            payment.setStatus(activeTotal.compareTo(payment.getAmount()) >= 0
                    ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);
        }
        if (!refunds.isEmpty()) {
            payment.setRefundTransactionId(refunds.get(0).getId());
        }
        paymentRepository.save(payment);
        log.info("Stripe refunds synced — paymentId={} refunded {} → {} status={}",
                payment.getId(), previous, activeTotal, payment.getStatus());

        if (refunds.isEmpty() || refunds.stream().allMatch(StripePaymentService::isAutoRefund)) return;

        // Transport errors propagate → non-2xx → Stripe retries (the transaction rolls back).
        OrderSnapshot order = orderServiceClient.getOrder(payment.getOrderId());
        List<String> rejected = new ArrayList<>();
        for (Refund refund : refunds.reversed()) { // oldest first: ledger in chronological order
            if (isAutoRefund(refund)) {
                // This payment was never accepted by the order (see autoRefund): its refund must not
                // touch the order's ledger — e.g. the order may be regularly PAID by another payment.
                continue;
            }
            String reference = "stripe-refund:" + refund.getId();
            BigDecimal amount = BigDecimal.valueOf(refund.getAmount()).movePointLeft(2);
            // Refund of an order-service return: recorded as RETURN and linked to it, so the return follows
            // the outcome (REFUNDED / REFUND_FAILED). Any other refund keeps the plain REFUND path.
            String returnReference = returnReference(refund);
            try {
                if (ACTIVE_REFUND_STATUSES.contains(refund.getStatus())) {
                    if (returnReference != null) {
                        orderServiceClient.recordRefund(payment.getOrderId(), order.id(), reference, amount,
                                "RETURN", null, refundReason(refund, payment), returnReference);
                    } else {
                        orderServiceClient.recordRefund(payment.getOrderId(), order.id(), reference, amount,
                                "REFUND", null, refundReason(refund, payment));
                    }
                } else if (FAILED_REFUND_STATUSES.contains(refund.getStatus())) {
                    // no-op on order-service if the refund had never been recorded there
                    // (except that a return still waiting for it becomes REFUND_FAILED)
                    String failure = "Refund " + refund.getStatus() + ": " + refund.getFailureReason();
                    if (returnReference != null) {
                        orderServiceClient.recordRefund(payment.getOrderId(), order.id(), "stripe-refund-failed:" + refund.getId(),
                                amount, "REFUND_FAILED", reference, failure, returnReference);
                    } else {
                        orderServiceClient.recordRefund(payment.getOrderId(), order.id(), "stripe-refund-failed:" + refund.getId(),
                                amount, "REFUND_FAILED", reference, failure);
                    }
                }
            } catch (OrderStateConflictException | OrderNotFoundException ex) {
                rejected.add(refund.getId() + " (" + ex.getMessage() + ")");
            }
        }
        if (!rejected.isEmpty()) {
            // Retrying would not help: the payment is right, the order needs a manual fix.
            log.error("MANUAL ACTION REQUIRED — refunds not recorded on order {} for payment {}: {}",
                    payment.getOrderId(), payment.getId(), rejected);
            payment.setNotes(truncate("Refund not recorded on order — align manually: " + rejected, 1000));
            paymentRepository.save(payment);
        }
    }

    private List<Refund> listRefunds(String paymentIntentId) {
        RefundListParams params = RefundListParams.builder().setPaymentIntent(paymentIntentId).setLimit(100L).build();
        StripeCollection<Refund> page = stripeCall("list Refunds", () -> stripe.v1().refunds().list(params));
        if (Boolean.TRUE.equals(page.getHasMore())) {
            log.warn("PaymentIntent {} has more than 100 refunds — only the latest 100 are synced", paymentIntentId);
        }
        return page.getData() != null ? page.getData() : List.of();
    }

    private static boolean isAutoRefund(Refund refund) {
        return refund.getMetadata() != null && "true".equals(refund.getMetadata().get("autoRefund"));
    }

    private static String returnReference(Refund refund) {
        String value = refund.getMetadata() != null ? refund.getMetadata().get(RETURN_REFERENCE_METADATA) : null;
        return value == null || value.isBlank() ? null : value;
    }

    private static String refundReason(Refund refund, Payment payment) {
        if (refund.getMetadata() != null && refund.getMetadata().get("reason") != null) {
            return refund.getMetadata().get("reason");
        }
        return payment.getRefundReason() != null ? payment.getRefundReason() : "Stripe refund";
    }

    private Optional<Payment> findPaymentByIntent(String paymentIntentId) {
        Optional<Payment> payment = paymentRepository.findByTransactionId(paymentIntentId);
        if (payment.isEmpty()) {
            log.warn("Stripe refund event for unknown PaymentIntent {} — ignored", paymentIntentId);
        }
        return payment;
    }

    // ─────────────────────────────────────────────────────────
    // REFUND REQUEST (from our API / admin)
    // ─────────────────────────────────────────────────────────

    /**
     * Asks Stripe to refund {@code amount} of a completed payment. Amount checks are done by the caller.
     *
     * <p>The payment goes to REFUND_PENDING (which also blocks a second concurrent refund); the final
     * amounts and the order update are applied by the {@code charge.refunded} webhook, the single place
     * where refunds are recorded — whether they start here or from the Stripe dashboard.
     */
    @Transactional
    public Payment requestRefund(Payment payment, BigDecimal amount, String reason) {
        return requestRefund(payment, amount, reason, null, null);
    }

    /**
     * @param returnReference RMA number of an order-service return: stored in the refund's metadata, so that
     *                        {@link #syncRefunds} records it in the order's ledger as a RETURN of that return
     * @param idempotencyKey  caller-provided key (order-service: one per return refund attempt), null = derived
     */
    @Transactional
    public Payment requestRefund(Payment payment, BigDecimal amount, String reason,
                                 String returnReference, String idempotencyKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new GatewayException("Stripe is not configured: set STRIPE_SECRET_KEY");
        }
        BigDecimal alreadyRefunded = payment.getRefundAmount() != null ? payment.getRefundAmount() : BigDecimal.ZERO;
        long amountMinor = amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        long targetTotalMinor = alreadyRefunded.add(amount).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();

        RefundCreateParams.Builder params = RefundCreateParams.builder()
                .setPaymentIntent(payment.getTransactionId())
                .setAmount(amountMinor)
                .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                .putMetadata("paymentId", payment.getId().toString())
                .putMetadata("orderId", payment.getOrderId().toString());
        if (reason != null) {
            params.putMetadata("reason", truncate(reason, 500));
        }
        if (returnReference != null && !returnReference.isBlank()) {
            params.putMetadata(RETURN_REFERENCE_METADATA, returnReference);
        }
        // Default: keyed on the cumulative target and on the last refund seen: a double click asks for the
        // same refund only once, while a retry after a failed refund (same target, new last refund) is a new
        // one. A caller key (always scoped to this payment) lets the caller decide what "the same refund" is.
        String lastRefund = payment.getRefundTransactionId() != null ? payment.getRefundTransactionId() : "none";
        String key = idempotencyKey != null && !idempotencyKey.isBlank()
                ? "refund-" + payment.getId() + "-ext-" + idempotencyKey
                : "refund-" + payment.getId() + "-" + targetTotalMinor + "-" + lastRefund;
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(key).build();

        Refund refund = stripeCall("create Refund", () -> stripe.v1().refunds().create(params.build(), options));

        payment.setStatus(PaymentStatus.REFUND_PENDING);
        payment.setRefundReason(truncate(reason, 500));
        payment.setRefundTransactionId(refund.getId());
        Payment saved = paymentRepository.save(payment);
        log.info("Stripe refund requested — paymentId={} amount={} refund={} status={}",
                payment.getId(), amount, refund.getId(), refund.getStatus());
        return saved;
    }

    // ─────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────

    private PaymentIntent paymentIntentOf(Event event) {
        return dataObjectOf(event, PaymentIntent.class);
    }

    private <T extends StripeObject> T dataObjectOf(Event event, Class<T> type) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        // getObject() is empty when the webhook's API version differs from the SDK's pinned one.
        StripeObject object = deserializer.getObject().orElseGet(() -> {
            try {
                return deserializer.deserializeUnsafe();
            } catch (EventDataObjectDeserializationException ex) {
                throw new GatewayException("Cannot deserialize Stripe event " + event.getId(), ex);
            }
        });
        if (type.isInstance(object)) {
            return type.cast(object);
        }
        throw new GatewayException("Stripe event " + event.getId() + " does not carry a " + type.getSimpleName());
    }

    private Optional<Payment> findPayment(PaymentIntent intent) {
        Optional<Payment> payment = paymentRepository.findByTransactionId(intent.getId());
        if (payment.isEmpty()) {
            // Not created by this service (e.g. a test event from the dashboard): acknowledge and ignore.
            log.warn("Stripe webhook for unknown PaymentIntent {} — ignored", intent.getId());
        }
        return payment;
    }

    private void markCancelled(Payment payment) {
        payment.setStatus(PaymentStatus.CANCELLED);
        payment.setCancelledAt(LocalDateTime.now());
        paymentRepository.save(payment);
        log.info("Stripe payment cancelled — paymentId={}", payment.getId());
    }

    private Currency parseCurrency(OrderSnapshot order) {
        if (order.currency() == null || order.currency().isBlank()) {
            throw new OrderStateConflictException("Order " + order.orderId() + " has no currency");
        }
        try {
            return Currency.valueOf(order.currency().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new OrderStateConflictException("Unsupported currency " + order.currency() + " for order " + order.orderId());
        }
    }

    /** All supported currencies (EUR, PEN, USD, CHF, GBP) have 2 decimals in Stripe. */
    private BigDecimal parseAmount(OrderSnapshot order) {
        if (order.total() == null || order.total().signum() <= 0) {
            throw new OrderStateConflictException("Order " + order.orderId() + " has no payable total");
        }
        return order.total().setScale(2, RoundingMode.HALF_UP);
    }

    private PaymentRegion resolveRegion(OrderSnapshot order) {
        String country = order.shippingAddress() != null ? order.shippingAddress().country() : null;
        if (country == null) return PaymentRegion.INTERNATIONAL;
        String code = country.trim().toUpperCase(Locale.ROOT);
        if (code.equals("IT")) return PaymentRegion.ITALY;
        if (code.equals("PE")) return PaymentRegion.PERU;
        if (SEPA_COUNTRIES.contains(code)) return PaymentRegion.EUROPE_SEPA;
        return PaymentRegion.INTERNATIONAL;
    }

    private String generateReference() {
        String date   = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "PAY-" + date + "-" + random;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    @FunctionalInterface
    private interface StripeCall<T> {
        T call() throws StripeException;
    }

    private static <T> T stripeCall(String action, StripeCall<T> call) {
        try {
            return call.call();
        } catch (StripeException ex) {
            throw new GatewayException("Stripe " + action + " failed: " + ex.getMessage(), ex);
        }
    }
}
