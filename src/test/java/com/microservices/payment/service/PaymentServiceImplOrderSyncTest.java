package com.microservices.payment.service;

import com.microservices.payment.client.order.OrderServiceClient;
import com.microservices.payment.dto.request.PaymentRequest;
import com.microservices.payment.exception.OrderServiceException;
import com.microservices.payment.gateway.GatewayResponse;
import com.microservices.payment.gateway.PaymentGatewayPort;
import com.microservices.payment.gateway.nexi.dto.NexiWebhookPayload;
import com.microservices.payment.model.entity.Payment;
import com.microservices.payment.model.enums.PaymentGateway;
import com.microservices.payment.model.enums.PaymentMethod;
import com.microservices.payment.model.enums.PaymentRegion;
import com.microservices.payment.model.enums.PaymentStatus;
import com.microservices.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Culqi / Nexi: a completed payment must move the order to PAID. */
class PaymentServiceImplOrderSyncTest {

    private final UUID orderId = UUID.randomUUID();

    private PaymentRepository paymentRepository;
    private PaymentGatewayPort gateway;
    private OrderServiceClient orderServiceClient;
    private PaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        gateway = mock(PaymentGatewayPort.class);
        when(gateway.supports(any(), any())).thenReturn(true);
        orderServiceClient = mock(OrderServiceClient.class);
        service = new PaymentServiceImpl(paymentRepository, List.of(gateway),
                mock(StripePaymentService.class), orderServiceClient);
    }

    @Test
    void culqi_completedCharge_marksOrderPaid() {
        when(gateway.charge(any())).thenReturn(GatewayResponse.builder()
                .status(PaymentStatus.COMPLETED).transactionId("chr_1").build());

        var response = service.createPayment(culqiRequest());

        verify(orderServiceClient).updateOrderPayment(orderId, response.getId(), "PAID");
    }

    @Test
    void culqi_declinedCharge_orderNotTouched() {
        when(gateway.charge(any())).thenReturn(GatewayResponse.builder()
                .status(PaymentStatus.DECLINED).failureCode("card_declined").build());

        service.createPayment(culqiRequest());

        verify(orderServiceClient, never()).updateOrderPayment(any(), any(), anyString());
    }

    @Test
    void culqi_orderServiceDown_paymentStillSavedWithNote() {
        when(gateway.charge(any())).thenReturn(GatewayResponse.builder()
                .status(PaymentStatus.COMPLETED).transactionId("chr_1").build());
        doThrow(new OrderServiceException("down"))
                .when(orderServiceClient).updateOrderPayment(eq(orderId), any(), eq("PAID"));

        var response = service.createPayment(culqiRequest()); // money taken: never fails

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(response.getNotes()).contains("Order not updated to PAID");
    }

    @Test
    void nexi_executed_marksOrderPaid() {
        Payment payment = Payment.builder().id(UUID.randomUUID()).orderId(orderId)
                .transactionId("sec_tok").paymentGateway(PaymentGateway.NEXI)
                .status(PaymentStatus.PENDING).retryCount(0).build();
        when(paymentRepository.findByTransactionId("sec_tok")).thenReturn(Optional.of(payment));

        NexiWebhookPayload payload = new NexiWebhookPayload();
        payload.setSecurityToken("sec_tok");
        NexiWebhookPayload.NexiOperation op = new NexiWebhookPayload.NexiOperation();
        op.setOperationId("op_1");
        op.setOperationResult("EXECUTED");
        payload.setOperation(op);

        service.handleNexiWebhook(payload);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(orderServiceClient).updateOrderPayment(orderId, payment.getId(), "PAID");
    }

    private PaymentRequest culqiRequest() {
        return PaymentRequest.builder()
                .orderId(orderId)
                .userId(UUID.randomUUID())
                .amount(new BigDecimal("50.00"))
                .paymentMethod(PaymentMethod.CREDIT_CARD)
                .paymentGateway(PaymentGateway.CULQI)
                .paymentRegion(PaymentRegion.PERU)
                .build();
    }
}
