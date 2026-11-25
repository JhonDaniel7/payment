package com.microservices.payment.config;

import com.microservices.payment.security.InternalApiKeyFilter;
import com.microservices.payment.security.JwtAuthenticationFilter;
import com.microservices.payment.security.recaptcha.RecaptchaFilter;
import com.microservices.payment.security.recaptcha.RecaptchaProperties;
import com.microservices.payment.security.recaptcha.RecaptchaVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RecaptchaProperties recaptchaProperties;
    private final RecaptchaVerifier recaptchaVerifier;

    @Value("${security.cors.allowed-origins}")
    private String allowedOrigins;

    /** The same shared secret sent to order-service (INTERNAL_API_KEY), accepted here from order-service. */
    @Value("${order-service.internal-api-key:}")
    private String internalApiKey;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Swagger / OpenAPI
                .requestMatchers(
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/api-docs",
                    "/api-docs/**"
                ).permitAll()
                // Stripe checkout — open to guest checkout too. The JWT, when present, is still
                // parsed by the filter and used to check that the order belongs to the caller.
                .requestMatchers(HttpMethod.POST, "/api/v1/payments/intents").permitAll()
                // Stripe webhook — no user auth, authenticated by the Stripe-Signature header
                .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/stripe").permitAll()
                // Nexi HPP webhook — Nexi calls this after the user completes payment
                .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/nexi").permitAll()
                // Culqi / Nexi payment creation by a logged-in customer
                .requestMatchers(HttpMethod.POST, "/api/v1/payments").authenticated()
                // Refund: admins, or order-service (internal key) refunding an approved return
                .requestMatchers(HttpMethod.POST, "/api/v1/payments/*/refund").hasAnyRole("ADMIN", InternalApiKeyFilter.ROLE)
                // Everything else on payments — list/read any payment, change status, refund,
                // cancel, and any future endpoint: admins only, closed by default
                .requestMatchers("/api/v1/payments/**").hasRole("ADMIN")
                // Everything else requires a valid JWT
                .anyRequest().authenticated()
            )
            // No/invalid token → 401; logged in but not allowed → 403. Both set the status directly:
            // the default sendError() re-dispatches to /error, where a stateless request is anonymous
            // again and a 403 would come out as 401.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                .accessDeniedHandler((request, response, denied) -> response.setStatus(HttpStatus.FORBIDDEN.value())))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // before the JWT filter, which then leaves an already authenticated internal caller alone
            .addFilterBefore(new InternalApiKeyFilter(internalApiKey), JwtAuthenticationFilter.class)
            // reCAPTCHA v3 only on the public endpoint called by the browser (not webhooks/internal/admin calls):
            // right after CORS, so preflights never reach it and a 403 still carries the CORS headers
            .addFilterAfter(new RecaptchaFilter(recaptchaProperties, recaptchaVerifier, Map.of(
                RecaptchaFilter.endpoint(HttpMethod.POST, "/api/v1/payments/intents"), "payment_intent"
            )), CorsFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
