package com.microservices.payment.security.recaptcha;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifier against a fake siteverify served by the JDK HTTP server. */
class RecaptchaVerifierTest {

    private HttpServer server;
    private final AtomicReference<String> answer = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private RecaptchaProperties properties;
    private RecaptchaVerifier verifier;

    @BeforeEach
    void startFakeGoogle() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/siteverify", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        properties = new RecaptchaProperties();
        properties.setSecretKey("test-secret");
        properties.setAllowedHostnames(List.of("localhost", "shop.example.com"));
        properties.setVerifyUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/siteverify");
        verifier = new RecaptchaVerifier(properties);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void google(boolean success, double score, String action, String hostname) {
        answer.set("""
                {"success":%s,"score":%s,"action":"%s","hostname":"%s","challenge_ts":"2026-11-25T10:00:00Z"}"""
                .formatted(success, score, action, hostname));
    }

    @Test
    void acceptsValidToken_andSendsSecretAndToken() {
        google(true, 0.9, "payment_intent", "Shop.Example.com");

        RecaptchaVerifier.Result result = verifier.verify("tok-123", "payment_intent");

        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(0.9);
        String body = URLDecoder.decode(receivedBody.get(), StandardCharsets.UTF_8);
        assertThat(body).contains("secret=test-secret").contains("response=tok-123");
    }

    @Test
    void scoreEqualToThresholdIsAccepted() {
        google(true, 0.5, "payment_intent", "localhost");
        assertThat(verifier.verify("t", "payment_intent").passed()).isTrue();
    }

    @Test
    void rejectsUnsuccessful() {
        answer.set("{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}");
        RecaptchaVerifier.Result result = verifier.verify("t", "payment_intent");
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).isEqualTo("not-successful");
        assertThat(result.errorCodes()).containsExactly("timeout-or-duplicate");
    }

    @Test
    void rejectsWrongAction() {
        google(true, 0.9, "return_lookup", "localhost");
        assertThat(verifier.verify("t", "payment_intent").reason()).isEqualTo("action-mismatch");
    }

    @Test
    void rejectsUnknownHostname() {
        google(true, 0.9, "payment_intent", "evil.example.org");
        assertThat(verifier.verify("t", "payment_intent").reason()).isEqualTo("hostname-not-allowed");
    }

    @Test
    void rejectsLowScore() {
        google(true, 0.3, "payment_intent", "localhost");
        assertThat(verifier.verify("t", "payment_intent").reason()).isEqualTo("score-below-threshold");
    }

    @Test
    void rejectsWhenGoogleUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        properties.setVerifyUrl("http://127.0.0.1:" + closedPort + "/siteverify");

        RecaptchaVerifier.Result result = verifier.verify("t", "payment_intent");

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).startsWith("siteverify-unavailable");
    }

    @Test
    void rejectsWhenSecretNotConfigured_withoutCallingGoogle() {
        properties.setSecretKey("");
        assertThat(verifier.verify("t", "payment_intent").reason()).isEqualTo("secret-key-not-configured");
        assertThat(receivedBody.get()).isNull();
    }
}
