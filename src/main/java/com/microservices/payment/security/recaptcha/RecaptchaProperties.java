package com.microservices.payment.security.recaptcha;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** reCAPTCHA v3 server-side verification ({@code recaptcha.*} in application.yml). */
@Configuration
@ConfigurationProperties(prefix = "recaptcha")
@Getter
@Setter
public class RecaptchaProperties {

    /** false only for local dev / tests: the filter then lets every request through. */
    private boolean enabled = true;

    /** Server-side secret key (env var RECAPTCHA_SECRET_KEY). Never logged, never sent to the browser. */
    private String secretKey;

    /** Minimum score (0.0 bot … 1.0 human) accepted. */
    private double minScore = 0.5;

    /** Hostnames the token may have been issued on (comma separated in the env var). */
    private List<String> allowedHostnames = List.of("localhost");

    private String verifyUrl = "https://www.google.com/recaptcha/api/siteverify";
}
