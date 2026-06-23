package com.microservices.payment.gateway.culqi;

import com.microservices.payment.dto.request.PaymentRequest;
import com.microservices.payment.exception.GatewayException;
import com.microservices.payment.gateway.GatewayResponse;
import com.microservices.payment.gateway.PaymentGatewayPort;
import com.microservices.payment.gateway.culqi.dto.CulqiChargeRequest;
import com.microservices.payment.gateway.culqi.dto.CulqiChargeResponse;
import com.microservices.payment.gateway.culqi.dto.CulqiRefundRequest;
import com.microservices.payment.gateway.culqi.dto.CulqiRefundResponse;
import com.microservices.payment.model.enums.PaymentMethod;
import com.microservices.payment.model.enums.PaymentRegion;
import com.microservices.payment.model.enums.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Set;

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

    private final RestClient restClient;

    public CulqiGatewayAdapter(@Qualifier("culqiRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public GatewayResponse charge(PaymentRequest request) {
        log.info("Culqi charge — orderId={} amount={} {}", request.getOrderId(), request.getAmount(), request.getCurrency());

        if (request.getSourceId() == null || request.getSourceId().isBlank()) {
            throw new GatewayException("Culqi requires sourceId (card token from Culqi.js)");
        }
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            throw new GatewayException("Culqi requires customer email");
        }

        int amountInCents = request.getAmount()
                .multiply(BigDecimal.valueOf(100))
                .intValue();

        CulqiChargeRequest body = CulqiChargeRequest.builder()
                .amount(amountInCents)
                .currencyCode(request.getCurrency().name())
                .email(request.getEmail())
                .sourceId(request.getSourceId())
                .capture(true)
                .description(request.getDescription())
                .installments(0)
                .build();

        try {
            CulqiChargeResponse response = restClient.post()
                    .uri("/charges")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(CulqiChargeResponse.class);

            if (response == null || response.getId() == null) {
                throw new GatewayException("Culqi returned empty response for orderId: " + request.getOrderId());
            }

            boolean success = response.getOutcome() != null
                    && "venta_exitosa".equals(response.getOutcome().getType());

            log.info("Culqi charge {} — orderId={} chargeId={}", success ? "succeeded" : "failed",
                    request.getOrderId(), response.getId());

            return GatewayResponse.builder()
                    .success(success)
                    .status(success ? PaymentStatus.COMPLETED : PaymentStatus.FAILED)
                    .transactionId(response.getId())
                    .requiresAction(false)
                    .build();

        } catch (RestClientResponseException e) {
            // Culqi returns error details in the 4xx response body
            log.warn("Culqi charge declined — orderId={} httpStatus={} body={}",
                    request.getOrderId(), e.getStatusCode(), e.getResponseBodyAsString());
            return GatewayResponse.builder()
                    .success(false)
                    .status(PaymentStatus.FAILED)
                    .gatewayErrorMessage(e.getResponseBodyAsString())
                    .requiresAction(false)
                    .build();
        } catch (Exception e) {
            log.error("Culqi charge error — orderId={} error={}", request.getOrderId(), e.getMessage());
            throw new GatewayException("Culqi charge failed: " + e.getMessage(), e);
        }
    }

    @Override
    public GatewayResponse refund(String transactionId, BigDecimal amount, String reason) {
        log.info("Culqi refund — chargeId={} amount={}", transactionId, amount);

        int amountInCents = amount.multiply(BigDecimal.valueOf(100)).intValue();

        CulqiRefundRequest body = CulqiRefundRequest.builder()
                .chargeId(transactionId)
                .amount(amountInCents)
                .reason(mapRefundReason(reason))
                .build();

        try {
            CulqiRefundResponse response = restClient.post()
                    .uri("/refunds")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(CulqiRefundResponse.class);

            if (response == null || response.getId() == null) {
                throw new GatewayException("Culqi returned empty refund response for chargeId: " + transactionId);
            }

            log.info("Culqi refund confirmed — refundId={}", response.getId());

            return GatewayResponse.builder()
                    .success(true)
                    .status(PaymentStatus.REFUNDED)
                    .transactionId(response.getId())
                    .requiresAction(false)
                    .build();

        } catch (RestClientResponseException e) {
            log.error("Culqi refund declined — chargeId={} httpStatus={} body={}",
                    transactionId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new GatewayException("Culqi refund failed: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("Culqi refund error — chargeId={} error={}", transactionId, e.getMessage());
            throw new GatewayException("Culqi refund failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(PaymentRegion region, PaymentMethod method) {
        return region == PaymentRegion.PERU && SUPPORTED_METHODS.contains(method);
    }

    private String mapRefundReason(String reason) {
        if (reason == null) return "solicitud_comprador";
        String lower = reason.toLowerCase();
        if (lower.contains("fraud"))    return "fraude";
        if (lower.contains("duplicat")) return "duplicado";
        if (lower.contains("quality"))  return "falla_calidad";
        return "solicitud_comprador";
    }
}