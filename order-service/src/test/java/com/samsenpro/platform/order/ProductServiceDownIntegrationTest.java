package com.samsenpro.platform.order;

import com.samsenpro.platform.commons.testing.TestJwts;
import com.samsenpro.platform.order.client.ResilientProductClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * product-service registrado pero CAÍDO: la instancia apunta a un puerto donde no escucha nadie
 * (conexión rechazada), como cuando el contenedor muere antes de que Eureka lo dé de baja.
 */
class ProductServiceDownIntegrationTest extends PostgresIntegrationTest {

    private static final int CLOSED_PORT = freePort();

    @DynamicPropertySource
    static void deadInstance(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri",
                () -> "http://localhost:" + CLOSED_PORT);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void resetCircuit() {
        circuitBreakers.circuitBreaker(ResilientProductClient.INSTANCE).reset();
    }

    @Test
    void unreachableProductServiceReturnsControlled503() throws Exception {
        createOrder()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.path").value("/api/orders"));
    }

    @Test
    void repeatedConnectionFailuresOpenTheCircuit() throws Exception {
        // Cada petición = 3 intentos fallidos; con 2 peticiones se superan las 4 llamadas mínimas
        createOrder().andExpect(status().isServiceUnavailable());
        createOrder().andExpect(status().isServiceUnavailable());

        assertThat(circuitBreakers.circuitBreaker(ResilientProductClient.INSTANCE).getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        long start = System.nanoTime();
        createOrder().andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(200);
    }

    private ResultActions createOrder() throws Exception {
        return mvc.perform(post("/api/orders")
                .header("Authorization", "Bearer " + TestJwts.user(UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":1,\"quantity\":1}]}"));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
