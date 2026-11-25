package com.microservices.payment.security.recaptcha;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Calls Google's siteverify for a reCAPTCHA v3 token and decides whether it is acceptable:
 * success, expected action, allowed hostname, score ≥ threshold. Every failure — Google unreachable
 * or slow included — is a rejection (fail-closed).
 */
@Component
public class RecaptchaVerifier {

    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final RecaptchaProperties properties;
    private final RestClient restClient;

    public RecaptchaVerifier(RecaptchaProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public Result verify(String token, String expectedAction) {
        if (!StringUtils.hasText(properties.getSecretKey())) {
            return Result.rejected("secret-key-not-configured", null);
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", properties.getSecretKey());
        form.add("response", token);

        SiteverifyResponse response;
        try {
            response = restClient.post()
                    .uri(properties.getVerifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(SiteverifyResponse.class);
        } catch (RestClientException ex) {
            // secret and token travel in the request body: only the exception type is reported
            return Result.rejected("siteverify-unavailable (" + ex.getClass().getSimpleName() + ")", null);
        }

        if (response == null) {
            return Result.rejected("siteverify-empty-response", null);
        }
        if (!response.success()) {
            return Result.rejected("not-successful", response);
        }
        if (!expectedAction.equals(response.action())) {
            return Result.rejected("action-mismatch", response);
        }
        if (!isAllowedHostname(response.hostname())) {
            return Result.rejected("hostname-not-allowed", response);
        }
        if (response.score() == null || response.score() < properties.getMinScore()) {
            return Result.rejected("score-below-threshold", response);
        }
        return new Result(true, null, response.action(), response.score(), response.hostname(), response.errorCodes());
    }

    private boolean isAllowedHostname(String hostname) {
        if (!StringUtils.hasText(hostname) || properties.getAllowedHostnames() == null) {
            return false;
        }
        String normalized = hostname.trim().toLowerCase(Locale.ROOT);
        return properties.getAllowedHostnames().stream()
                .filter(StringUtils::hasText)
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    /** Outcome of a verification; action/score/hostname come from Google's answer (null when there was none). */
    public record Result(boolean passed, String reason, String action, Double score, String hostname,
                         List<String> errorCodes) {

        static Result rejected(String reason, SiteverifyResponse response) {
            return response == null
                    ? new Result(false, reason, null, null, null, null)
                    : new Result(false, reason, response.action(), response.score(), response.hostname(), response.errorCodes());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SiteverifyResponse(boolean success,
                              Double score,
                              String action,
                              String hostname,
                              @JsonProperty("error-codes") List<String> errorCodes) {
    }
}
