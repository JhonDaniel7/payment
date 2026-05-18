package com.microservices.payment.dto.response;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class ErrorResponse {

    int status;
    String error;
    String message;
    String path;
    LocalDateTime timestamp;
}