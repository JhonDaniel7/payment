package com.microservices.payment.controller;

import com.microservices.payment.dto.request.RefundRequest;
import com.microservices.payment.dto.response.PaymentResponse;
import com.microservices.payment.model.enums.PaymentStatus;
import com.microservices.payment.security.InternalApiKeyFilter;
import com.microservices.payment.service.PaymentService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may call the refund endpoint: admins and order-service (internal key) - nobody else. */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentRefundSecurityTest {

    private static final String BODY = """
            {"refundAmount":105.90,"refundReason":"Reso RMA-7K2M9QX4TB",
             "returnReference":"RMA-7K2M9QX4TB","idempotencyKey":"return-RMA-7K2M9QX4TB-0"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentService paymentService;

    @Value("${order-service.internal-api-key}")
    private String internalApiKey;
    @Value("${security.jwt.secret-key}")
    private String jwtSecret;

    private final UUID paymentId = UUID.randomUUID();

    @Test
    void orderService_withTheInternalKey_canRefund_andTheReturnFieldsReachTheService() throws Exception {
        when(paymentService.refund(eq(paymentId), any())).thenReturn(
                PaymentResponse.builder().id(paymentId).status(PaymentStatus.REFUND_PENDING).refundTransactionId("re_1").build());

        mockMvc.perform(refund().header(InternalApiKeyFilter.HEADER, internalApiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND_PENDING"));

        ArgumentCaptor<RefundRequest> request = ArgumentCaptor.forClass(RefundRequest.class);
        verify(paymentService).refund(eq(paymentId), request.capture());
        assertThat(request.getValue().getRefundAmount()).isEqualByComparingTo(new BigDecimal("105.90"));
        assertThat(request.getValue().getReturnReference()).isEqualTo("RMA-7K2M9QX4TB");
        assertThat(request.getValue().getIdempotencyKey()).isEqualTo("return-RMA-7K2M9QX4TB-0");
    }

    @Test
    void admin_canStillRefund() throws Exception {
        when(paymentService.refund(eq(paymentId), any())).thenReturn(PaymentResponse.builder().id(paymentId).build());

        mockMvc.perform(refund().header("Authorization", bearer("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void wrongInternalKey_unauthorized() throws Exception {
        mockMvc.perform(refund().header(InternalApiKeyFilter.HEADER, "wrong")).andExpect(status().isUnauthorized());
        verify(paymentService, never()).refund(any(), any());
    }

    @Test
    void anonymousOrCustomer_refused() throws Exception {
        mockMvc.perform(refund()).andExpect(status().isUnauthorized());
        mockMvc.perform(refund().header("Authorization", bearer("CLIENTE"))).andExpect(status().isForbidden());
        verify(paymentService, never()).refund(any(), any());
    }

    @Test
    void internalKey_grantsNothingBeyondRefunds() throws Exception {
        mockMvc.perform(get("/api/v1/payments/" + paymentId).header(InternalApiKeyFilter.HEADER, internalApiKey))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder refund() {
        return post("/api/v1/payments/" + paymentId + "/refund").contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    private String bearer(String role) {
        return "Bearer " + Jwts.builder()
                .subject("user-1")
                .claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret)))
                .compact();
    }
}
