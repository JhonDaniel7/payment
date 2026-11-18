package com.microservices.payment.service;

import com.microservices.payment.client.order.OrderServiceClient;
import com.microservices.payment.client.order.OrderSnapshot;
import com.microservices.payment.dto.request.CreatePaymentIntentRequest;
import com.microservices.payment.dto.response.CreatePaymentIntentResponse;
import com.microservices.payment.exception.InvalidWebhookSignatureException;
import com.microservices.payment.exception.OrderNotFoundException;
import com.microservices.payment.exception.OrderServiceException;
import com.microservices.payment.exception.OrderStateConflictException;
import com.microservices.payment.model.entity.Payment;
import com.microservices.payment.model.enums.Currency;
import com.microservices.payment.model.enums.PaymentGateway;
import com.microservices.payment.model.enums.PaymentRegion;
import com.microservices.payment.model.enums.PaymentStatus;
import com.microservices.payment.repository.PaymentRepository;
import com.stripe.Stripe;
import com.stripe.StripeClient;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.service.PaymentIntentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripePaymentServiceTest {

    private static final String WEBHOOK_SECRET = "whsec_test_secret";

    private final UUID orderId = UUID.randomUUID();

    private PaymentIntentService paymentIntents;
    private com.stripe.service.RefundService refundService;
    private OrderServiceClient orderServiceClient;
    private PaymentRepository paymentRepository;
    private StripePaymentService service;

    @BeforeEach
    void setUp() {
        StripeClient stripe = mock(StripeClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        paymentIntents = mock(PaymentIntentService.class);
        when(stripe.v1().paymentIntents()).thenReturn(paymentIntents);
        refundService = mock(com.stripe.service.RefundService.class);
        when(stripe.v1().refunds()).thenReturn(refundService);

        orderServiceClient = mock(OrderServiceClient.class);
        paymentRepository = mock(PaymentRepository.class);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });

        service = new StripePaymentService(stripe, orderServiceClient, paymentRepository);
        ReflectionTestUtils.setField(service, "secretKey", "sk_test_dummy");
        ReflectionTestUtils.setField(service, "webhookSecret", WEBHOOK_SECRET);
    }

    // ─────────────────────────────────────────────────────────
    // createIntent
    // ─────────────────────────────────────────────────────────

    @Test
    void createIntent_usesServerSideTotalInMinorUnits_andReturnsClientSecret() throws Exception {
        when(orderServiceClient.getOrder(orderId)).thenReturn(order(null, "PENDING_PAYMENT", "59.9", "EUR", "IT"));
        when(paymentRepository.findFirstByOrderIdAndPaymentGatewayOrderByCreatedAtDesc(orderId, PaymentGateway.STRIPE))
                .thenReturn(Optional.empty());
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.empty());
        when(paymentIntents.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenReturn(intent("pi_123", "requires_payment_method", 5990L, "eur", "pi_123_secret_abc"));

        CreatePaymentIntentResponse response = service.createIntent(new CreatePaymentIntentRequest(orderId, PaymentGateway.STRIPE), null);

        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(paymentIntents).create(params.capture(), options.capture());
        assertThat(params.getValue().getAmount()).isEqualTo(5990L);
        assertThat(params.getValue().getCurrency()).isEqualTo("eur");
        assertThat(params.getValue().getMetadata()).containsEntry("orderId", orderId.toString());
        assertThat(options.getValue().getIdempotencyKey()).contains(orderId.toString());

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getTransactionId()).isEqualTo("pi_123");
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("59.90");
        assertThat(saved.getValue().getCurrency()).isEqualTo(Currency.EUR);
        assertThat(saved.getValue().getPaymentRegion()).isEqualTo(PaymentRegion.ITALY);
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.PENDING);

        assertThat(response.provider()).isEqualTo(PaymentGateway.STRIPE);
        assertThat(response.clientSecret()).isEqualTo("pi_123_secret_abc");
        assertThat(response.paymentId()).isEqualTo(saved.getValue().getId());
    }

    @Test
    void createIntent_orderNotPendingPayment_conflict() {
        when(orderServiceClient.getOrder(orderId)).thenReturn(order(null, "PAID", "10", "EUR", "IT"));

        assertThatThrownBy(() -> service.createIntent(new CreatePaymentIntentRequest(orderId, PaymentGateway.STRIPE), null))
                .isInstanceOf(OrderStateConflictException.class);
    }

    @Test
    void createIntent_orderOfAnotherCustomer_notFound() {
        when(orderServiceClient.getOrder(orderId)).thenReturn(order("alice", "PENDING_PAYMENT", "10", "EUR", "IT"));

        assertThatThrownBy(() -> service.createIntent(new CreatePaymentIntentRequest(orderId, PaymentGateway.STRIPE), "bob"))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service.createIntent(new CreatePaymentIntentRequest(orderId, PaymentGateway.STRIPE), null))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void createIntent_unsupportedProvider_badRequest() {
        assertThatThrownBy(() -> service.createIntent(new CreatePaymentIntentRequest(orderId, PaymentGateway.NEXI), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createIntent_reusesConfirmableIntent_insteadOfCreatingANewOne() throws Exception {
        when(orderServiceClient.getOrder(orderId)).thenReturn(order("alice", "PENDING_PAYMENT", "20.00", "EUR", "IT"));
        Payment previous = stripePayment("pi_old", PaymentStatus.DECLINED);
        when(paymentRepository.findFirstByOrderIdAndPaymentGatewayOrderByCreatedAtDesc(orderId, PaymentGateway.STRIPE))
                .thenReturn(Optional.of(previous));
        when(paymentIntents.retrieve("pi_old"))
                .thenReturn(intent("pi_old", "requires_payment_method", 2000L, "eur", "pi_old_secret"));

        CreatePaymentIntentResponse response = service.createIntent(new CreatePaymentIntentRequest(orderId, PaymentGateway.STRIPE), "alice");

        assertThat(response.paymentId()).isEqualTo(previous.getId());
        assertThat(response.clientSecret()).isEqualTo("pi_old_secret");
        verify(paymentIntents, never()).create(any(PaymentIntentCreateParams.class), any(RequestOptions.class));
    }

    @Test
    void createIntent_previousIntentAlreadySucceeded_conflict() throws Exception {
        when(orderServiceClient.getOrder(orderId)).thenReturn(order(null, "PENDING_PAYMENT", "20.00", "EUR", "IT"));
        when(paymentRepository.findFirstByOrderIdAndPaymentGatewayOrderByCreatedAtDesc(orderId, PaymentGateway.STRIPE))
                .thenReturn(Optional.of(stripePayment("pi_old", PaymentStatus.PENDING)));
        when(paymentIntents.retrieve("pi_old"))
                .thenReturn(intent("pi_old", "succeeded", 2000L, "eur", "pi_old_secret"));

        assertThatThrownBy(() -> service.createIntent(new CreatePaymentIntentRequest(orderId, PaymentGateway.STRIPE), null))
                .isInstanceOf(OrderStateConflictException.class);
        verify(paymentIntents, never()).create(any(PaymentIntentCreateParams.class), any(RequestOptions.class));
    }

    // ─────────────────────────────────────────────────────────
    // webhook
    // ─────────────────────────────────────────────────────────

    @Test
    void webhook_invalidSignature_rejected() {
        String payload = event("payment_intent.succeeded", "pi_123", "succeeded");

        assertThatThrownBy(() -> service.handleWebhook(payload, "t=1,v1=deadbeef"))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> service.handleWebhook(payload, null))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        verify(orderServiceClient, never()).updateOrderPayment(any(), any(), anyString());
    }

    @Test
    void webhook_succeeded_completesPaymentAndMarksOrderPaid() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.PENDING);
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        String payload = event("payment_intent.succeeded", "pi_123", "succeeded");

        service.handleWebhook(payload, sign(payload));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getCompletedAt()).isNotNull();
        verify(orderServiceClient).updateOrderPayment(orderId, payment.getId(), "PAID");
    }

    @Test
    void webhook_succeeded_orderServiceDown_propagatesSoStripeRetries() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.PENDING);
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        org.mockito.Mockito.doThrow(new OrderServiceException("down"))
                .when(orderServiceClient).updateOrderPayment(eq(orderId), any(), eq("PAID"));
        String payload = event("payment_intent.succeeded", "pi_123", "succeeded");

        assertThatThrownBy(() -> service.handleWebhook(payload, sign(payload)))
                .isInstanceOf(OrderServiceException.class);
        verify(refundService, never()).create(any(com.stripe.param.RefundCreateParams.class), any(RequestOptions.class));
    }

    // ─────────────────────────────────────────────────────────
    // automatic refund — order paid after being cancelled
    // ─────────────────────────────────────────────────────────

    @Test
    void webhook_succeeded_orderNoLongerPayable_refundsAutomatically() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.PENDING);
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        org.mockito.Mockito.doThrow(new OrderStateConflictException("Order is CANCELLED, expected PENDING_PAYMENT"))
                .when(orderServiceClient).updateOrderPayment(eq(orderId), any(), eq("PAID"));
        when(refundService.create(any(com.stripe.param.RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(refund("re_auto", "pending", 2000));
        String payload = event("payment_intent.succeeded", "pi_123", "succeeded");

        service.handleWebhook(payload, sign(payload));

        ArgumentCaptor<com.stripe.param.RefundCreateParams> params = ArgumentCaptor.forClass(com.stripe.param.RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(refundService).create(params.capture(), options.capture());
        assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_123");
        assertThat(params.getValue().getAmount()).isNull(); // full refund
        assertThat(((java.util.Map<?, ?>) params.getValue().getMetadata()).get("autoRefund")).isEqualTo("true");
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("auto-refund-" + payment.getId());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(payment.getRefundTransactionId()).isEqualTo("re_auto");
        assertThat(payment.getNotes()).contains("Automatic refund").contains("CANCELLED");
    }

    @Test
    void chargeRefunded_forAutoRefund_updatesPaymentButNeverTouchesTheOrder() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.REFUND_PENDING);
        payment.setRefundTransactionId("re_auto");
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        com.stripe.model.Refund auto = refund("re_auto", "succeeded", 2000);
        auto.setMetadata(java.util.Map.of("autoRefund", "true"));
        stripeRefunds(auto);
        String payload = chargeRefundedEvent();

        service.handleWebhook(payload, sign(payload));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundAmount()).isEqualByComparingTo("20.00");
        verify(orderServiceClient, never()).getOrder(any());
        verify(orderServiceClient, never()).recordRefund(any(), any(), anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void webhook_succeeded_redeliveredAfterAutoRefund_neverRefundsTwice() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.REFUND_PENDING);
        payment.setRefundTransactionId("re_auto");
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        org.mockito.Mockito.doThrow(new OrderStateConflictException("Order is CANCELLED"))
                .when(orderServiceClient).updateOrderPayment(eq(orderId), any(), eq("PAID"));
        String payload = event("payment_intent.succeeded", "pi_123", "succeeded");

        service.handleWebhook(payload, sign(payload));

        verify(refundService, never()).create(any(com.stripe.param.RefundCreateParams.class), any(RequestOptions.class));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
    }

    @Test
    void webhook_succeeded_autoRefundRefusedByStripe_propagatesSoStripeRetries() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.PENDING);
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        org.mockito.Mockito.doThrow(new OrderNotFoundException(orderId))
                .when(orderServiceClient).updateOrderPayment(eq(orderId), any(), eq("PAID"));
        when(refundService.create(any(com.stripe.param.RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new com.stripe.exception.ApiConnectionException("network down"));
        String payload = event("payment_intent.succeeded", "pi_123", "succeeded");

        assertThatThrownBy(() -> service.handleWebhook(payload, sign(payload)))
                .isInstanceOf(com.microservices.payment.exception.GatewayException.class);
    }

    @Test
    void webhook_failed_marksDeclined_withoutTouchingTheOrder() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.PENDING);
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        String payload = event("payment_intent.payment_failed", "pi_123", "requires_payment_method");

        service.handleWebhook(payload, sign(payload));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payment.getFailureCode()).isEqualTo("insufficient_funds");
        verify(orderServiceClient, never()).updateOrderPayment(any(), any(), anyString());
    }

    @Test
    void webhook_lateFailure_neverDowngradesCompletedPayment() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.COMPLETED);
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        String payload = event("payment_intent.payment_failed", "pi_123", "requires_payment_method");

        service.handleWebhook(payload, sign(payload));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    // ─────────────────────────────────────────────────────────
    // refunds
    // ─────────────────────────────────────────────────────────

    @Test
    void chargeRefunded_partial_updatesPaymentAndRecordsRefundOnOrder() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.COMPLETED);
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        when(orderServiceClient.getOrder(orderId)).thenReturn(order(null, "PAID", "20.00", "EUR", "IT"));
        stripeRefunds(refund("re_1", "succeeded", 500));
        String payload = chargeRefundedEvent();

        service.handleWebhook(payload, sign(payload));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundAmount()).isEqualByComparingTo("5.00");
        assertThat(payment.getRefundTransactionId()).isEqualTo("re_1");
        verify(orderServiceClient).recordRefund(eq(orderId), eq(42L), eq("stripe-refund:re_1"),
                amountEq("5.00"), eq("REFUND"), isNull(), anyString());
    }

    @Test
    void chargeRefunded_redelivered_sendsSameReference_soOrderServiceIgnoresIt() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.PARTIALLY_REFUNDED);
        payment.setRefundAmount(new BigDecimal("5.00"));
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        when(orderServiceClient.getOrder(orderId)).thenReturn(order(null, "PARTIALLY_REFUNDED", "20.00", "EUR", "IT"));
        stripeRefunds(refund("re_1", "succeeded", 500));
        String payload = chargeRefundedEvent();

        service.handleWebhook(payload, sign(payload));
        service.handleWebhook(payload, sign(payload));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundAmount()).isEqualByComparingTo("5.00");
        // always the same idempotency reference — never a new, double-counted one
        verify(orderServiceClient, org.mockito.Mockito.times(2)).recordRefund(eq(orderId), eq(42L),
                eq("stripe-refund:re_1"), any(), eq("REFUND"), isNull(), anyString());
    }

    @Test
    void chargeRefunded_twoRefunds_fullyRefunded_eachRecordedUnderItsOwnId() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.PARTIALLY_REFUNDED);
        payment.setRefundAmount(new BigDecimal("5.00"));
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        when(orderServiceClient.getOrder(orderId)).thenReturn(order(null, "PARTIALLY_REFUNDED", "20.00", "EUR", "IT"));
        stripeRefunds(refund("re_2", "succeeded", 1500), refund("re_1", "succeeded", 500)); // newest first
        String payload = chargeRefundedEvent();

        service.handleWebhook(payload, sign(payload));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundAmount()).isEqualByComparingTo("20.00");
        assertThat(payment.getRefundTransactionId()).isEqualTo("re_2");
        verify(orderServiceClient).recordRefund(eq(orderId), eq(42L), eq("stripe-refund:re_1"),
                amountEq("5.00"), eq("REFUND"), isNull(), anyString());
        verify(orderServiceClient).recordRefund(eq(orderId), eq(42L), eq("stripe-refund:re_2"),
                amountEq("15.00"), eq("REFUND"), isNull(), anyString());
    }

    @Test
    void chargeRefunded_orderRejectsRefund_flagsManualActionWithoutFailing() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.COMPLETED);
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        when(orderServiceClient.getOrder(orderId)).thenReturn(order(null, "PAID", "20.00", "EUR", "IT"));
        stripeRefunds(refund("re_1", "succeeded", 500));
        org.mockito.Mockito.doThrow(new OrderStateConflictException("exceeds"))
                .when(orderServiceClient).recordRefund(any(), any(), anyString(), any(), anyString(), any(), anyString());
        String payload = chargeRefundedEvent();

        service.handleWebhook(payload, sign(payload));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getNotes()).contains("align manually");
    }

    @Test
    void refundFailed_onlyRefund_paymentBackToCompleted_andOrderEntryCancelled() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.PARTIALLY_REFUNDED);
        payment.setRefundAmount(new BigDecimal("5.00"));
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        when(orderServiceClient.getOrder(orderId)).thenReturn(order(null, "PARTIALLY_REFUNDED", "20.00", "EUR", "IT"));
        stripeRefunds(refund("re_1", "failed", 500));
        String payload = refundEvent("refund.failed", "re_1", "failed", 500);

        service.handleWebhook(payload, sign(payload));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getRefundAmount()).isEqualByComparingTo("0");
        assertThat(payment.getNotes()).contains("re_1").contains("refund them another way");
        verify(orderServiceClient).recordRefund(eq(orderId), eq(42L), eq("stripe-refund-failed:re_1"),
                amountEq("5.00"), eq("REFUND_FAILED"), eq("stripe-refund:re_1"), anyString());
        verify(orderServiceClient, never()).recordRefund(any(), any(), anyString(), any(), eq("REFUND"), any(), anyString());
    }

    @Test
    void refundFailed_oneOfTwo_keepsTheOtherRefund() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.REFUNDED);
        payment.setRefundAmount(new BigDecimal("15.00"));
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        when(orderServiceClient.getOrder(orderId)).thenReturn(order(null, "PARTIALLY_REFUNDED", "20.00", "EUR", "IT"));
        stripeRefunds(refund("re_2", "failed", 1000), refund("re_1", "succeeded", 500));
        String payload = refundEvent("refund.failed", "re_2", "failed", 1000);

        service.handleWebhook(payload, sign(payload));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundAmount()).isEqualByComparingTo("5.00");
        verify(orderServiceClient).recordRefund(eq(orderId), eq(42L), eq("stripe-refund:re_1"),
                amountEq("5.00"), eq("REFUND"), isNull(), anyString());
        verify(orderServiceClient).recordRefund(eq(orderId), eq(42L), eq("stripe-refund-failed:re_2"),
                amountEq("10.00"), eq("REFUND_FAILED"), eq("stripe-refund:re_2"), anyString());
    }

    @Test
    void refundUpdated_canceledFromDashboard_isHandledAsFailure() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.REFUNDED);
        payment.setRefundAmount(new BigDecimal("20.00"));
        when(paymentRepository.findByTransactionId("pi_123")).thenReturn(Optional.of(payment));
        when(orderServiceClient.getOrder(orderId)).thenReturn(order(null, "REFUNDED", "20.00", "EUR", "IT"));
        stripeRefunds(refund("re_1", "canceled", 2000));
        String payload = refundEvent("refund.updated", "re_1", "canceled", 2000);

        service.handleWebhook(payload, sign(payload));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(orderServiceClient).recordRefund(eq(orderId), eq(42L), eq("stripe-refund-failed:re_1"),
                any(), eq("REFUND_FAILED"), eq("stripe-refund:re_1"), anyString());
    }

    @Test
    void refundUpdated_notAFailure_isIgnored() throws Exception {
        String payload = refundEvent("refund.updated", "re_1", "succeeded", 500);

        service.handleWebhook(payload, sign(payload));

        verify(refundService, never()).list(any(com.stripe.param.RefundListParams.class));
        verify(orderServiceClient, never()).recordRefund(any(), any(), anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void requestRefund_callsStripeWithIdempotencyKey_andSetsRefundPending() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.COMPLETED);
        com.stripe.model.Refund created = refund("re_9", "pending", 750);
        when(refundService.create(any(com.stripe.param.RefundCreateParams.class), any(RequestOptions.class))).thenReturn(created);

        service.requestRefund(payment, new BigDecimal("7.50"), "Reso cliente");

        ArgumentCaptor<com.stripe.param.RefundCreateParams> params = ArgumentCaptor.forClass(com.stripe.param.RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(refundService).create(params.capture(), options.capture());
        assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_123");
        assertThat(params.getValue().getAmount()).isEqualTo(750L);
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("refund-" + payment.getId() + "-750-none");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(payment.getRefundTransactionId()).isEqualTo("re_9");
        // amounts are recorded only by the webhook
        assertThat(payment.getRefundAmount()).isNull();
    }

    @Test
    void requestRefund_retryAfterFailedRefund_usesANewIdempotencyKey() throws Exception {
        Payment payment = stripePayment("pi_123", PaymentStatus.COMPLETED);
        payment.setRefundTransactionId("re_failed"); // previous attempt failed, payment back to COMPLETED
        when(refundService.create(any(com.stripe.param.RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(refund("re_10", "pending", 750));

        service.requestRefund(payment, new BigDecimal("7.50"), null);

        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(refundService).create(any(com.stripe.param.RefundCreateParams.class), options.capture());
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("refund-" + payment.getId() + "-750-re_failed");
    }

    private void stripeRefunds(com.stripe.model.Refund... refunds) throws Exception {
        com.stripe.model.StripeCollection<com.stripe.model.Refund> page = new com.stripe.model.StripeCollection<>();
        page.setData(java.util.List.of(refunds));
        page.setHasMore(false);
        when(refundService.list(any(com.stripe.param.RefundListParams.class))).thenReturn(page);
    }

    private static com.stripe.model.Refund refund(String id, String status, long amount) {
        com.stripe.model.Refund refund = new com.stripe.model.Refund();
        refund.setId(id);
        refund.setStatus(status);
        refund.setAmount(amount);
        refund.setPaymentIntent("pi_123");
        return refund;
    }

    private static BigDecimal amountEq(String expected) {
        return org.mockito.ArgumentMatchers.argThat(a -> a != null && a.compareTo(new BigDecimal(expected)) == 0);
    }

    private static String chargeRefundedEvent() {
        return """
                {"id":"evt_r","object":"event","api_version":"%s","type":"charge.refunded",
                 "data":{"object":{"id":"ch_1","object":"charge","payment_intent":"pi_123",
                 "amount":2000,"currency":"eur"}}}
                """.formatted(Stripe.API_VERSION);
    }

    private static String refundEvent(String type, String refundId, String status, long amount) {
        return """
                {"id":"evt_f","object":"event","api_version":"%s","type":"%s",
                 "data":{"object":{"id":"%s","object":"refund","status":"%s","amount":%d,
                 "payment_intent":"pi_123","charge":"ch_1","failure_reason":"expired_or_canceled_card"}}}
                """.formatted(Stripe.API_VERSION, type, refundId, status, amount);
    }

    // ─────────────────────────────────────────────────────────
    // fixtures
    // ─────────────────────────────────────────────────────────

    private OrderSnapshot order(String customerId, String status, String total, String currency, String country) {
        return new OrderSnapshot(orderId, customerId, status, currency, new BigDecimal(total),
                new OrderSnapshot.Customer("test@example.com"), new OrderSnapshot.ShippingAddress(country), 42L);
    }

    private Payment stripePayment(String intentId, PaymentStatus status) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .transactionId(intentId)
                .paymentGateway(PaymentGateway.STRIPE)
                .amount(new BigDecimal("20.00"))
                .currency(Currency.EUR)
                .status(status)
                .retryCount(0)
                .build();
    }

    private static PaymentIntent intent(String id, String status, long amount, String currency, String clientSecret) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId(id);
        intent.setStatus(status);
        intent.setAmount(amount);
        intent.setCurrency(currency);
        intent.setClientSecret(clientSecret);
        return intent;
    }

    private static String event(String type, String intentId, String intentStatus) {
        String lastError = type.endsWith("payment_failed")
                ? ",\"last_payment_error\":{\"type\":\"card_error\",\"code\":\"card_declined\",\"decline_code\":\"insufficient_funds\",\"message\":\"Your card has insufficient funds.\"}"
                : "";
        return """
                {"id":"evt_1","object":"event","api_version":"%s","type":"%s",
                 "data":{"object":{"id":"%s","object":"payment_intent","status":"%s",
                 "amount":2000,"amount_received":%d,"currency":"eur"%s}}}
                """.formatted(Stripe.API_VERSION, type, intentId, intentStatus,
                type.endsWith("succeeded") ? 2000 : 0, lastError);
    }

    private static String sign(String payload) throws Exception {
        return Webhook.Signature.generateSignatureHeader(payload, WEBHOOK_SECRET);
    }
}
