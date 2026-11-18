package com.microservices.payment.client.order;

import com.microservices.payment.exception.OrderNotFoundException;
import com.microservices.payment.exception.OrderServiceException;
import com.microservices.payment.exception.OrderStateConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for order-service, addressing orders by their public UUID.
 */
@Slf4j
@Component
public class OrderServiceClient {

    private final RestClient restClient;

    public OrderServiceClient(@Qualifier("orderServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public OrderSnapshot getOrder(UUID orderId) {
        try {
            OrderSnapshot order = restClient.get()
                    .uri("/orders/public/{publicId}", orderId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(OrderSnapshot.class);
            if (order == null) {
                throw new OrderServiceException("Empty response from order-service for order " + orderId);
            }
            return order;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new OrderNotFoundException(orderId);
            }
            throw new OrderServiceException("order-service returned " + ex.getStatusCode() + " for order " + orderId, ex);
        } catch (RestClientException ex) {
            throw new OrderServiceException("order-service unreachable while reading order " + orderId, ex);
        }
    }

    /**
     * Appends an entry to the order's refund ledger; order-service recomputes the order status.
     * Idempotent on {@code externalReference}: resubmitting the same value is a no-op.
     *
     * @param type                      REFUND, or REFUND_FAILED to cancel a previous refund entry
     * @param reversesExternalReference only for REFUND_FAILED: the entry being cancelled
     * @throws OrderStateConflictException if order-service rejects it (e.g. exceeds the refundable total)
     */
    public void recordRefund(UUID orderId, Long orderInternalId, String externalReference, BigDecimal amount,
                             String type, String reversesExternalReference, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("externalReference", externalReference);
        body.put("amount", amount);
        body.put("type", type);
        if (reversesExternalReference != null) body.put("reversesExternalReference", reversesExternalReference);
        if (reason != null) body.put("reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
        try {
            restClient.post()
                    .uri("/orders/{id}/refunds", orderInternalId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("order-service refund recorded — orderId={} amount={} ref={}", orderId, amount, externalReference);
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            if (code == HttpStatus.NOT_FOUND.value()) {
                throw new OrderNotFoundException(orderId);
            }
            if (code == HttpStatus.BAD_REQUEST.value() || code == HttpStatus.CONFLICT.value()) {
                throw new OrderStateConflictException(
                        "order-service refused refund for order " + orderId + ": " + ex.getResponseBodyAsString());
            }
            throw new OrderServiceException("order-service returned " + ex.getStatusCode() + " recording refund for order " + orderId, ex);
        } catch (RestClientException ex) {
            throw new OrderServiceException("order-service unreachable while recording refund for order " + orderId, ex);
        }
    }

    /**
     * Applies the final payment outcome (PAID / CANCELLED) to the order. Idempotent on order-service side.
     *
     * @throws OrderStateConflictException if the order can no longer transition (e.g. already CANCELLED)
     */
    public void updateOrderPayment(UUID orderId, UUID paymentId, String status) {
        try {
            restClient.patch()
                    .uri("/orders/public/{publicId}/payment", orderId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("paymentId", paymentId, "status", status))
                    .retrieve()
                    .toBodilessEntity();
            log.info("order-service updated — orderId={} status={} paymentId={}", orderId, status, paymentId);
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            if (code == HttpStatus.NOT_FOUND.value()) {
                throw new OrderNotFoundException(orderId);
            }
            if (code == HttpStatus.CONFLICT.value()) {
                throw new OrderStateConflictException(
                        "order-service refused " + status + " for order " + orderId + ": " + ex.getResponseBodyAsString());
            }
            throw new OrderServiceException("order-service returned " + ex.getStatusCode() + " updating order " + orderId, ex);
        } catch (RestClientException ex) {
            throw new OrderServiceException("order-service unreachable while updating order " + orderId, ex);
        }
    }
}
