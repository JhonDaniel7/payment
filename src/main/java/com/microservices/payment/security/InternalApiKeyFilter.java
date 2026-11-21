package com.microservices.payment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Service-to-service authentication, mirror of order-service's filter: a caller presenting the shared
 * internal key (header {@value #HEADER}, env var INTERNAL_API_KEY) is authenticated as {@code ROLE_INTERNAL}.
 * Used by order-service to refund approved returns. No header → continue (JWT / anonymous);
 * header present but wrong → 401 immediately.
 *
 * <p>Not a {@code @Component}: registered only in the security chain, never as a plain servlet filter.
 */
@Slf4j
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";
    public static final String ROLE = "INTERNAL";

    private final String internalApiKey;

    public InternalApiKeyFilter(String internalApiKey) {
        this.internalApiKey = internalApiKey;
        if (!StringUtils.hasText(internalApiKey)) {
            log.error("order-service.internal-api-key is EMPTY — internal calls from order-service will get 401");
        }
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (!StringUtils.hasText(presented)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!matches(presented)) {
            // No key value in the log, only where the call came from
            log.warn("Rejected X-Internal-Api-Key — {} {} from {}: INTERNAL_API_KEY differs from the caller's",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
        filterChain.doFilter(request, response);
    }

    /** Constant-time comparison; an unconfigured (blank) key never matches. */
    private boolean matches(String presented) {
        if (!StringUtils.hasText(internalApiKey)) {
            return false;
        }
        return MessageDigest.isEqual(internalApiKey.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
