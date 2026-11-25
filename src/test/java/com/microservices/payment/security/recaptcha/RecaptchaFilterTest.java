package com.microservices.payment.security.recaptcha;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecaptchaFilterTest {

    private RecaptchaProperties properties;
    private RecaptchaVerifier verifier;
    private RecaptchaFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RecaptchaProperties();
        properties.setSecretKey("test-secret");
        verifier = mock(RecaptchaVerifier.class);
        filter = new RecaptchaFilter(properties, verifier, Map.of(
                RecaptchaFilter.endpoint(HttpMethod.POST, "/api/v1/payments/intents"), "payment_intent"));
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static void assertRecaptchaFailed(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"RECAPTCHA_FAILED\"}");
    }

    @Test
    void validToken_passesWithTheEndpointAction() throws Exception {
        when(verifier.verify("tok", "payment_intent"))
                .thenReturn(new RecaptchaVerifier.Result(true, null, "payment_intent", 0.9, "localhost", List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments/intents");
        request.addHeader(RecaptchaFilter.HEADER, "tok");
        MockFilterChain chain = new MockFilterChain();

        assertThat(run(request, chain).getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void missingHeader_403_withoutCallingGoogle() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertRecaptchaFailed(run(new MockHttpServletRequest("POST", "/api/v1/payments/intents"), chain));
        assertThat(chain.getRequest()).isNull();
        verify(verifier, never()).verify(anyString(), anyString());
    }

    @Test
    void failedVerification_403() throws Exception {
        when(verifier.verify("tok", "payment_intent"))
                .thenReturn(new RecaptchaVerifier.Result(false, "action-mismatch", "checkout_order", 0.9, "localhost", List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments/intents");
        request.addHeader(RecaptchaFilter.HEADER, "tok");
        MockFilterChain chain = new MockFilterChain();

        assertRecaptchaFailed(run(request, chain));
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void trailingSlashIsStillProtected() throws Exception {
        assertRecaptchaFailed(run(new MockHttpServletRequest("POST", "/api/v1/payments/intents/"), new MockFilterChain()));
    }

    @Test
    void otherEndpointsAndMethodsAreUntouched() throws Exception {
        for (MockHttpServletRequest request : List.of(
                new MockHttpServletRequest("OPTIONS", "/api/v1/payments/intents"),
                new MockHttpServletRequest("POST", "/api/v1/webhooks/stripe"),
                new MockHttpServletRequest("POST", "/api/v1/payments/abc/refund"),
                new MockHttpServletRequest("POST", "/api/v1/payments"))) {
            MockFilterChain chain = new MockFilterChain();
            assertThat(run(request, chain).getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
        }
        verify(verifier, never()).verify(anyString(), anyString());
    }

    @Test
    void disabled_letsEverythingThrough() throws Exception {
        properties.setEnabled(false);
        MockFilterChain chain = new MockFilterChain();
        assertThat(run(new MockHttpServletRequest("POST", "/api/v1/payments/intents"), chain).getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }
}
