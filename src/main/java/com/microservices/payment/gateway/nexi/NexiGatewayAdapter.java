package com.microservices.payment.gateway.nexi;

import com.microservices.payment.dto.request.PaymentRequest;
import com.microservices.payment.gateway.GatewayResponse;
import com.microservices.payment.gateway.PaymentGatewayPort;
import com.microservices.payment.model.enums.PaymentMethod;
import com.microservices.payment.model.enums.PaymentRegion;
import com.microservices.payment.model.enums.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

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

    @Value("${gateway.nexi.api-key:}")
    private String apiKey;

    @Value("${gateway.nexi.base-url:https://xpay.nexigroup.com/api/phoenix-0.0/psp/api/v1}")
    private String baseUrl;

    @Override
    public GatewayResponse charge(PaymentRequest request) {
        log.info("Nexi charge — orderId={} amount={} {}", request.getOrderId(), request.getAmount(), request.getCurrency());

        // TODO: build NexiOrderRequest and call POST {baseUrl}/orders/hpp
        // NexiOrderRequest body = NexiOrderRequest.builder()
        //         .order(NexiOrder.builder()
        //                 .orderId(request.getOrderId().toString())
        //                 .amount(request.getAmount().multiply(BigDecimal.valueOf(100)).toPlainString()) // cents
        //                 .currency(request.getCurrency().name())
        //                 .build())
        //         .build();
        // NexiOrderResponse raw = restClient.post()
        //         .uri(baseUrl + "/orders/hpp")
        //         .header("X-Api-Key", apiKey)
        //         .body(body)
        //         .retrieve()
        //         .body(NexiOrderResponse.class);
        // return mapToGatewayResponse(raw);

        // EU payments typically start with REQUIRES_ACTION for 3DS
        boolean requires3ds = request.getPaymentRegion() == PaymentRegion.EUROPE_SEPA
                || request.getPaymentMethod() == PaymentMethod.CREDIT_CARD
                || request.getPaymentMethod() == PaymentMethod.DEBIT_CARD;

        return GatewayResponse.builder()
                .success(true)
                .status(requires3ds ? PaymentStatus.REQUIRES_ACTION : PaymentStatus.COMPLETED)
                .transactionId("NEXI-SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .requiresAction(requires3ds)
                .actionUrl(requires3ds ? "https://3ds.nexi.test/authenticate?token=sim" : null)
                .threeDsStatus(requires3ds ? "Y" : null)
                .build();
    }

    @Override
    public GatewayResponse refund(String transactionId, BigDecimal amount, String reason) {
        log.info("Nexi refund — transactionId={} amount={}", transactionId, amount);

        // TODO: call POST {baseUrl}/operations/{transactionId}/refunds
        // NexiRefundRequest body = NexiRefundRequest.builder()
        //         .amount(amount.multiply(BigDecimal.valueOf(100)).toPlainString())
        //         .currency("EUR")
        //         .build();

        return GatewayResponse.builder()
                .success(true)
                .status(PaymentStatus.REFUNDED)
                .transactionId("NEXI-REF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .requiresAction(false)
                .build();
    }

    @Override
    public boolean supports(PaymentRegion region, PaymentMethod method) {
        return SUPPORTED_REGIONS.contains(region) && SUPPORTED_METHODS.contains(method);
    }
}