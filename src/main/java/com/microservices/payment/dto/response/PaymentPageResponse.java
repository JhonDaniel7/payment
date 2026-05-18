package com.microservices.payment.dto.response;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class PaymentPageResponse {

    List<PaymentResponse> content;
    long totalElements;
    int totalPages;
    int currentPage;
    int pageSize;
    boolean hasNext;
    boolean hasPrevious;
}