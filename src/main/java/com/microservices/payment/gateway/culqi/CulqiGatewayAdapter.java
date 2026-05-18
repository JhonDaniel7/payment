package com.microservices.payment.gateway.culqi;

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
public class CulqiGatewayAdapter implements PaymentGatewayPort {

    private static final Set<PaymentMethod> SUPPORTED_METHODS = Set.of(
            PaymentMethod.CREDIT_CARD,
            PaymentMethod.DEBIT_CARD,
            PaymentMethod.YAPE,
            PaymentMethod.PLIN,
            PaymentMethod.PAGO_EFECTIVO,
            PaymentMethod.CULQI,
            PaymentMethod.IZIPAY,
            PaymentMethod.SAFETY_PAY
    );

    @Value("${gateway.culqi.api-key:}")
    private String apiKey;

    @Value("${gateway.culqi.base-url:https://api.culqi.com/v2}")
    private String baseUrl;

    @Override
    public GatewayResponse charge(PaymentRequest request) {
        log.info("Culqi charge — orderId={} amount={} {}", request.getOrderId(), request.getAmount(), request.getCurrency());

        // TODO: build CulqiChargeRequest and call POST {baseUrl}/charges
        // CulqiChargeRequest body = CulqiChargeRequest.builder()
        //         .amount(request.getAmount().multiply(BigDecimal.valueOf(100)).intValue()) // Culqi expects cents
        //         .currencyCode(request.getCurrency().name())
        //         .email(...)      // retrieve from user service
        //         .sourceId(...)   // token from frontend (Culqi.js)
        //         .build();
        // CulqiChargeResponse raw = restClient.post()
        //         .uri(baseUrl + "/charges")
        //         .header("Authorization", "Bearer " + apiKey)
        //         .body(body)
        //         .retrieve()
        //         .body(CulqiChargeResponse.class);
        // return mapToGatewayResponse(raw);

        return GatewayResponse.builder()
                .success(true)
                .status(PaymentStatus.COMPLETED)
                .transactionId("CULQI-SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .requiresAction(false)
                .build();
    }

    @Override
    public GatewayResponse refund(String transactionId, BigDecimal amount, String reason) {
        log.info("Culqi refund — transactionId={} amount={}", transactionId, amount);

        // TODO: call POST {baseUrl}/refunds
        // CulqiRefundRequest body = CulqiRefundRequest.builder()
        //         .chargeId(transactionId)
        //         .amount(amount.multiply(BigDecimal.valueOf(100)).intValue())
        //         .reason(reason)
        //         .build();

        return GatewayResponse.builder()
                .success(true)
                .status(PaymentStatus.REFUNDED)
                .transactionId("CULQI-REF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .requiresAction(false)
                .build();
    }

    @Override
    public boolean supports(PaymentRegion region, PaymentMethod method) {
        return region == PaymentRegion.PERU && SUPPORTED_METHODS.contains(method);
    }
}