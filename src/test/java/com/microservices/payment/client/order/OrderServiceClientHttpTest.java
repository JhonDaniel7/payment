package com.microservices.payment.client.order;

import com.microservices.payment.config.OrderServiceConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP round trip: the configured RestClient must be able to send PATCH. */
class OrderServiceClientHttpTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void updateOrderPayment_sendsPatchWithInternalKeyAndBody() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            key.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        OrderServiceConfig config = new OrderServiceConfig();
        ReflectionTestUtils.setField(config, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/ecommerce-bj");
        ReflectionTestUtils.setField(config, "internalApiKey", "secret-key");
        OrderServiceClient client = new OrderServiceClient(config.orderServiceRestClient());

        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        client.updateOrderPayment(orderId, paymentId, "PAID");

        assertThat(method.get()).isEqualTo("PATCH");
        assertThat(path.get()).isEqualTo("/ecommerce-bj/orders/public/" + orderId + "/payment");
        assertThat(key.get()).isEqualTo("secret-key");
        assertThat(body.get()).contains("\"status\":\"PAID\"").contains(paymentId.toString());
    }
}
