package com.microservices.payment.gateway.nexi;

import com.microservices.payment.dto.request.PaymentRequest;
import com.microservices.payment.exception.GatewayException;
import com.microservices.payment.gateway.GatewayResponse;
import com.microservices.payment.gateway.PaymentGatewayPort;
import com.microservices.payment.gateway.nexi.dto.NexiOrderRequest;
import com.microservices.payment.gateway.nexi.dto.NexiOrderResponse;
import com.microservices.payment.gateway.nexi.dto.NexiRefundRequest;
import com.microservices.payment.gateway.nexi.dto.NexiRefundResponse;
import com.microservices.payment.model.enums.PaymentMethod;
import com.microservices.payment.model.enums.PaymentRegion;
import com.microservices.payment.model.enums.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.Set;

@Slf4j
@Component
public class NexiGatewayAdapter implements PaymentGatewayPort {

    private static final Set<PaymentMethod> SUPPORTED_METHODS = Set.of(
            PaymentMethod.CREDIT_CARD,
            PaymentMethod.DEBIT_CARD,
            PaymentMethod.NEXI,
            PaymentMethod.POSTEPAY,
            PaymentMethod.BANCOMAT,
            PaymentMethod.SATISPAY,
            PaymentMethod.SCALAPAY,
            PaymentMethod.KLARNA,
            PaymentMethod.BONIFICO,
            PaymentMethod.SEPA_CREDIT_TRANSFER,
            PaymentMethod.SEPA_DIRECT_DEBIT
    );

    private static final Set<PaymentRegion> SUPPORTED_REGIONS = Set.of(
            PaymentRegion.ITALY,
            PaymentRegion.EUROPE_SEPA
    );

    private final RestClient restClient;

    @Value("${gateway.nexi.notification-url:}")
    private String notificationUrl;

    @Value("${gateway.nexi.result-url:}")
    private String defaultResultUrl;

    @Value("${gateway.nexi.cancel-url:}")
    private String defaultCancelUrl;

    public NexiGatewayAdapter(@Qualifier("nexiRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public GatewayResponse charge(PaymentRequest request) {
        log.info("Nexi charge — orderId={} amount={} {}", request.getOrderId(), request.getAmount(), request.getCurrency());

        String amountInCents = request.getAmount()
                .multiply(BigDecimal.valueOf(100))
                .toBigInteger()
                .toString();

        String resultUrl  = request.getCallbackUrl() != null ? request.getCallbackUrl() : defaultResultUrl;
        String cancelUrl  = request.getCancelUrl()   != null ? request.getCancelUrl()   : defaultCancelUrl;

        NexiOrderRequest body = NexiOrderRequest.builder()
                .order(NexiOrderRequest.NexiOrder.builder()
                        .orderId(request.getOrderId().toString())
                        .amount(amountInCents)
                        .currency(request.getCurrency().name())
                        .customerId(request.getUserId().toString())
                        .description(request.getDescription())
                        .build())
                .paymentSession(NexiOrderRequest.NexiPaymentSession.builder()
                        .actionType("PAY")
                        .amount(amountInCents)
                        .currency(request.getCurrency().name())
                        .language("ITA")
                        .resultUrl(resultUrl)
                        .cancelUrl(cancelUrl)
                        .notificationUrl(notificationUrl)
                        .build())
                .build();

        try {
            NexiOrderResponse response = restClient.post()
                    .uri("/orders/2steps/init")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(NexiOrderResponse.class);

            if (response == null || response.getSessionId() == null) {
                throw new GatewayException("Nexi returned empty session for orderId: " + request.getOrderId());
            }

            log.info("Nexi session created — orderId={} sessionId={}", request.getOrderId(), response.getSessionId());

            // securityToken stored in transactionId for webhook validation.
            // sessionId returned to the frontend to initialize XPay.js.
            return GatewayResponse.builder()
                    .success(true)
                    .status(PaymentStatus.REQUIRES_ACTION)
                    .transactionId(response.getSecurityToken())
                    .sessionId(response.getSessionId())
                    .requiresAction(true)
                    .threeDsStatus("PENDING")
                    .build();

        } catch (RestClientException e) {
            log.error("Nexi charge failed — orderId={} error={}", request.getOrderId(), e.getMessage());
            throw new GatewayException("Nexi charge failed: " + e.getMessage(), e);
        }
    }

    @Override
    public GatewayResponse refund(String transactionId, BigDecimal amount, String reason) {
        log.info("Nexi refund — operationId={} amount={}", transactionId, amount);

        String amountInCents = amount
                .multiply(BigDecimal.valueOf(100))
                .toBigInteger()
                .toString();

        NexiRefundRequest body = NexiRefundRequest.builder()
                .amount(amountInCents)
                .currency("EUR")
                .description(reason)
                .build();

        try {
            NexiRefundResponse response = restClient.post()
                    .uri("/operations/{operationId}/refunds", transactionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(NexiRefundResponse.class);

            if (response == null || response.getOperationId() == null) {
                throw new GatewayException("Nexi returned empty refund response for operationId: " + transactionId);
            }

            log.info("Nexi refund confirmed — refundOperationId={}", response.getOperationId());

            return GatewayResponse.builder()
                    .success(true)
                    .status(PaymentStatus.REFUNDED)
                    .transactionId(response.getOperationId())
                    .requiresAction(false)
                    .build();

        } catch (RestClientException e) {
            log.error("Nexi refund failed — operationId={} error={}", transactionId, e.getMessage());
            throw new GatewayException("Nexi refund failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(PaymentRegion region, PaymentMethod method) {
        return SUPPORTED_REGIONS.contains(region) && SUPPORTED_METHODS.contains(method);
    }
}