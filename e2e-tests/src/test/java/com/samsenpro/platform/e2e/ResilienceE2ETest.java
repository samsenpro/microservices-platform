package com.samsenpro.platform.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.samsenpro.platform.e2e.Platform.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Fallos reales sobre el stack de docker compose: failure → timeout → retry → circuit breaker → fallback.
 * Requiere el perfil chaos en product-service:
 * {@code docker compose -f docker-compose.yml -f docker-compose.chaos.yml up -d}
 * (valores de config-repo/order-service.yml: timeout 2 s, 3 intentos, circuito con mínimo 5 llamadas y
 * 10 s en OPEN).
 */
@Order(3)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResilienceE2ETest {

    private static long productId;

    @BeforeAll
    static void requireChaosMode() {
        assumeTrue(chaosAvailable(), "product-service must run with the chaos profile (docker-compose.chaos.yml)");
        productId = Platform.createProduct("E2E resilience item", "10.00", 1_000);
    }

    @AfterEach
    void recover() {
        chaos("NONE", 0);
        waitUntilOrdersWork();
    }

    @Test
    @Order(1)
    void transient503IsRetriedThreeTimesThenReportedAsUnavailable() {
        chaos("ERROR_503", 0);
        String correlationId = "e2e-retry-" + UUID.randomUUID();

        Platform.Response response = createOrder(correlationId);

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.body().get("code").asText()).isEqualTo("PRODUCT_SERVICE_UNAVAILABLE");
        assertThat(response.body().get("correlationId").asText()).isEqualTo(correlationId);
        // product-service registró 3 fallos inyectados con el mismo correlation ID: 1 intento + 2 reintentos
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(productLogLines(correlationId, "Chaos: injecting")).hasSize(3));
    }

    @Test
    @Order(2)
    void circuitOpensFailsFastAndClosesAgainWhenTheDependencyRecovers() {
        chaos("ERROR_503", 0);
        // 2 pedidos = 6 llamadas fallidas (> 5 mínimas, 100 % de fallos) -> OPEN
        createOrder("e2e-cb-1-" + UUID.randomUUID());
        createOrder("e2e-cb-2-" + UUID.randomUUID());
        assertThat(circuitState()).isEqualTo("OPEN");

        // OPEN: responde al instante con el fallback, sin llamar a product-service
        String correlationId = "e2e-cb-open-" + UUID.randomUUID();
        long start = System.nanoTime();
        Platform.Response fastFail = createOrder(correlationId);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(fastFail.status()).isEqualTo(503);
        assertThat(fastFail.body().get("code").asText()).isEqualTo("PRODUCT_SERVICE_UNAVAILABLE");
        assertThat(elapsedMs).isLessThan(1_000);
        assertThat(productLogLines(correlationId, "")).isEmpty();

        // La dependencia se recupera: tras 10 s en OPEN pasa a HALF_OPEN y las llamadas de prueba lo cierran
        chaos("NONE", 0);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(createOrder("e2e-cb-recover-" + UUID.randomUUID()).status()).isEqualTo(201);
            assertThat(circuitState()).isEqualTo("CLOSED");
        });
    }

    @Test
    @Order(3)
    void slowProductServiceHitsTheTimeoutInsteadOfBlocking() {
        chaos("DELAY", 5_000);

        long start = System.nanoTime();
        Platform.Response response = createOrder("e2e-timeout-" + UUID.randomUUID());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.status()).isEqualTo(504);
        assertThat(response.body().get("code").asText()).isEqualTo("PRODUCT_SERVICE_TIMEOUT");
        // 3 intentos x 2 s de timeout + backoff (0,2 s + 0,4 s), no 3 x 5 s
        assertThat(elapsedMs).isBetween(6_000L, 9_000L);
    }

    @Test
    @Order(4)
    void productServiceDownIsAControlledFailureAndRecoversOnRestart() {
        Platform.compose("stop", "product-service");
        try {
            Platform.Response response = createOrder("e2e-down-" + UUID.randomUUID());
            assertThat(response.status()).isEqualTo(503);
            assertThat(response.body().get("code").asText()).isEqualTo("PRODUCT_SERVICE_UNAVAILABLE");
        } finally {
            Platform.compose("start", "product-service");
        }
        // Se vuelve a registrar en Eureka y los pedidos funcionan sin reiniciar nada más
        waitUntilOrdersWork();
    }

    @Test
    @Order(5)
    void productDatabaseDownIsReportedAs503AndRecoversAutomatically() {
        Platform.compose("stop", "products-db");
        try {
            Platform.Response response = Platform.get("/api/products/" + productId, Platform.userToken());
            assertThat(response.status()).isEqualTo(503);
            // Según el momento: la petición llega a product-service (DATABASE_UNAVAILABLE) o Eureka ya lo marcó
            // DOWN por su health check y el gateway no lo enruta (SERVICE_UNAVAILABLE)
            assertThat(response.body().get("code").asText()).isIn("DATABASE_UNAVAILABLE", "SERVICE_UNAVAILABLE");
        } finally {
            Platform.compose("start", "products-db");
        }
        waitUntilOrdersWork();
    }

    // ------------------------------------------------------------------ helpers

    private static Platform.Response createOrder(String correlationId) {
        return Platform.post("/api/orders", Platform.userToken(), Platform.orderOf(productId, 1), correlationId);
    }

    private static void waitUntilOrdersWork() {
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(3)).ignoreExceptions()
                .untilAsserted(() -> {
                    assertThat(createOrder("e2e-probe-" + UUID.randomUUID()).status()).isEqualTo(201);
                    assertThat(circuitState()).isEqualTo("CLOSED");
                });
    }

    private static boolean chaosAvailable() {
        try {
            Platform.compose("exec", "-T", "product-service", "wget", "-qO-", "http://localhost:8092/internal/chaos");
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static void chaos(String mode, long delayMs) {
        Platform.compose("exec", "-T", "product-service", "wget", "-qO-", "--post-data=",
                "http://localhost:8092/internal/chaos?mode=" + mode + "&delayMs=" + delayMs + "&failureRate=1.0");
    }

    /** Estado del circuit breaker, leído del Actuator de order-service (solo accesible dentro de la red). */
    private static String circuitState() {
        String json = Platform.compose("exec", "-T", "order-service", "wget", "-qO-",
                "--header=Authorization: Bearer " + Platform.adminToken(),
                "http://localhost:8093/actuator/circuitbreakers");
        try {
            return JSON.readTree(json).at("/circuitBreakers/productService/state").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected actuator response: " + json, e);
        }
    }

    private static List<JsonNode> productLogLines(String correlationId, String messageFragment) {
        String logs = Platform.compose("logs", "--no-log-prefix", "--since", "5m", "product-service");
        return logs.lines()
                .filter(line -> line.startsWith("{") && line.contains(correlationId))
                .map(line -> {
                    try {
                        return JSON.readTree(line);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .filter(node -> node.path("message").asText().contains(messageFragment))
                .toList();
    }
}
