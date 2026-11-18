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
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.model.StripeObject;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentUpdateParams;
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
            stripeCall("cancel PaymentIntent", () -> stripe.v1().paymentIntents().cancel(intent.getId()));
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
            // TODO: refunds — update the payment and call order-service POST /orders/{id}/refunds
            case "charge.refunded"               -> log.info("Stripe charge.refunded received — not handled yet (event {})", event.getId());
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
            // Money was collected but the order can no longer be paid (e.g. cancelled meanwhile).
            // Retrying would not help: flag it for a manual refund and acknowledge the event.
            log.error("MANUAL ACTION REQUIRED — payment {} succeeded but order {} could not be marked PAID: {}",
                    payment.getId(), payment.getOrderId(), ex.getMessage());
            payment.setNotes(truncate("Paid but order not updatable — refund required: " + ex.getMessage(), 1000));
            paymentRepository.save(payment);
        }
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

    private void onCanceled(PaymentIntent intent) {
        findPayment(intent)
                .filter(p -> !SETTLED_STATUSES.contains(p.getStatus()))
                .ifPresent(this::markCancelled);
    }

    // ─────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────

    private PaymentIntent paymentIntentOf(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        // getObject() is empty when the webhook's API version differs from the SDK's pinned one.
        StripeObject object = deserializer.getObject().orElseGet(() -> {
            try {
                return deserializer.deserializeUnsafe();
            } catch (EventDataObjectDeserializationException ex) {
                throw new GatewayException("Cannot deserialize Stripe event " + event.getId(), ex);
            }
        });
        if (object instanceof PaymentIntent intent) {
            return intent;
        }
        throw new GatewayException("Stripe event " + event.getId() + " does not carry a PaymentIntent");
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
