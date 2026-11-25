package com.microservices.payment.security.recaptcha;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * reCAPTCHA v3 check on the public endpoints called by the frontend. Active ONLY on the configured
 * method + path pairs, each with the action the frontend sets when it generates the token
 * (header {@value #HEADER}, a fresh single-use token per request). Missing token, failed verification
 * or Google unreachable → 403 {@code {"code":"RECAPTCHA_FAILED"}} (fail-closed).
 *
 * <p>Not a {@code @Component}: registered only in the security chain, right after the CORS filter, so
 * preflights are answered before reaching it and the 403 still carries the CORS headers the browser
 * needs to read the body. Never logs the token or the secret key.
 */
@Slf4j
public class RecaptchaFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Recaptcha-Token";
    public static final String ERROR_CODE = "RECAPTCHA_FAILED";
    private static final byte[] ERROR_BODY = ("{\"code\":\"" + ERROR_CODE + "\"}").getBytes(StandardCharsets.UTF_8);

    private final RecaptchaProperties properties;
    private final RecaptchaVerifier verifier;
    /** {@code endpoint(POST, "/api/v1/payments/intents")} → "payment_intent" */
    private final Map<String, String> actionByEndpoint;

    public RecaptchaFilter(RecaptchaProperties properties, RecaptchaVerifier verifier, Map<String, String> actionByEndpoint) {
        this.properties = properties;
        this.verifier = verifier;
        this.actionByEndpoint = Map.copyOf(actionByEndpoint);
        if (!properties.isEnabled()) {
            log.warn("reCAPTCHA verification is DISABLED (recaptcha.enabled=false) — never in production");
        } else if (!StringUtils.hasText(properties.getSecretKey())) {
            log.error("recaptcha.secret-key is EMPTY — set RECAPTCHA_SECRET_KEY: every protected request will get 403 {}", ERROR_CODE);
        }
    }

    /** Key of the endpoint → action map. */
    public static String endpoint(HttpMethod method, String path) {
        return method.name() + " " + path;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !properties.isEnabled() || expectedAction(request) == null;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String expectedAction = expectedAction(request);
        String token = request.getHeader(HEADER);

        if (!StringUtils.hasText(token)) {
            log.warn("reCAPTCHA rejected — {} {} from {}: missing {} header (expectedAction={})",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr(), HEADER, expectedAction);
            reject(response);
            return;
        }

        RecaptchaVerifier.Result result = verifier.verify(token.trim(), expectedAction);
        if (!result.passed()) {
            log.warn("reCAPTCHA rejected — {} {} from {}: reason={} expectedAction={} action={} score={} hostname={} errorCodes={}",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr(), result.reason(),
                    expectedAction, result.action(), result.score(), result.hostname(), result.errorCodes());
            reject(response);
            return;
        }

        log.debug("reCAPTCHA ok — {} {} action={} score={} hostname={}",
                request.getMethod(), request.getRequestURI(), result.action(), result.score(), result.hostname());
        filterChain.doFilter(request, response);
    }

    private String expectedAction(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // a trailing slash must not let the request slip past the check
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return actionByEndpoint.get(request.getMethod() + " " + path);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(ERROR_BODY.length);
        response.getOutputStream().write(ERROR_BODY);
    }
}
