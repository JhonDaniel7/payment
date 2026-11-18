package com.microservices.payment.exception;

import com.microservices.payment.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(PaymentNotFoundException ex, HttpServletRequest request) {
        log.warn("Payment not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidPaymentStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleInvalidState(InvalidPaymentStateException ex, HttpServletRequest request) {
        log.warn("Invalid payment state: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "INVALID_PAYMENT_STATE", ex.getMessage(), request);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleOrderNotFound(OrderNotFoundException ex, HttpServletRequest request) {
        log.warn("Order not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(OrderStateConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleOrderState(OrderStateConflictException ex, HttpServletRequest request) {
        log.warn("Invalid order state: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "INVALID_ORDER_STATE", ex.getMessage(), request);
    }

    @ExceptionHandler(OrderServiceException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ErrorResponse handleOrderService(OrderServiceException ex, HttpServletRequest request) {
        log.error("order-service error: {}", ex.getMessage(), ex);
        return build(HttpStatus.BAD_GATEWAY, "ORDER_SERVICE_ERROR", "Order service unavailable", request);
    }

    @ExceptionHandler(InvalidWebhookSignatureException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleWebhookSignature(InvalidWebhookSignatureException ex, HttpServletRequest request) {
        log.warn("Rejected webhook: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "INVALID_SIGNATURE", ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed or invalid request body", request);
    }

    @ExceptionHandler(GatewayException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ErrorResponse handleGateway(GatewayException ex, HttpServletRequest request) {
        log.error("Gateway error: {}", ex.getMessage(), ex);
        return build(HttpStatus.BAD_GATEWAY, "GATEWAY_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", message);
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request);
    }

    private ErrorResponse build(HttpStatus status, String error, String message, HttpServletRequest request) {
        return ErrorResponse.builder()
                .status(status.value())
                .error(error)
                .message(message)
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
    }
}