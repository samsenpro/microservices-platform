package com.samsenpro.platform.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.samsenpro.platform.e2e.Platform.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Flujo completo: Register → Login → JWT → Create Product → Create Order → Order Service → Product Service.
 */
@Order(1)
class OrderFlowE2ETest {

    @Test
    void registerLoginCreateProductAndOrderThroughTheGateway() {
        // Register + Login: el usuario obtiene su JWT
        String userToken = Platform.userToken();
        Platform.Response me = Platform.get("/api/users/me", userToken);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("role").asText()).isEqualTo("USER");

        // Create Product (ADMIN)
        long productId = Platform.createProduct("E2E keyboard", "49.90", 10);

        // Create Order: order-service consulta product-service (vía Eureka) para validar precio y stock
        String correlationId = "e2e-" + UUID.randomUUID();
        Platform.Response order = Platform.post("/api/orders", userToken, Platform.orderOf(productId, 2), correlationId);

        assertThat(order.status()).isEqualTo(201);
        assertThat(order.headers("X-Correlation-ID")).containsExactly(correlationId);
        assertThat(order.body().get("status").asText()).isEqualTo("CREATED");
        assertThat(order.body().get("userId").asText()).isEqualTo(me.body().get("id").asText());
        assertThat(order.body().get("total").decimalValue()).isEqualByComparingTo("99.80");
        assertThat(order.body().get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("49.90");

        // El pedido se puede consultar después
        Platform.Response fetched = Platform.get("/api/orders/" + order.body().get("id").asText(), userToken);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body().get("total").decimalValue()).isEqualByComparingTo("99.80");
    }

    @Test
    void businessValidationUsesTheLiveProductData() {
        String userToken = Platform.userToken();
        long scarce = Platform.createProduct("E2E scarce item", "5.00", 1);

        Platform.Response tooMany = Platform.post("/api/orders", userToken, Platform.orderOf(scarce, 5));
        assertThat(tooMany.status()).isEqualTo(409);
        assertThat(tooMany.body().get("code").asText()).isEqualTo("INSUFFICIENT_STOCK");

        Platform.Response missing = Platform.post("/api/orders", userToken, Platform.orderOf(987_654_321L, 1));
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().get("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    /**
     * El mismo correlation ID y el mismo traceId aparecen en los logs de gateway, order-service y
     * product-service: la petición se puede seguir de punta a punta.
     */
    @Test
    void oneRequestCanBeFollowedAcrossAllServiceLogs() {
        String userToken = Platform.userToken();
        long productId = Platform.createProduct("E2E traced item", "1.00", 5);
        String correlationId = "e2e-trace-" + UUID.randomUUID();

        assertThat(Platform.post("/api/orders", userToken, Platform.orderOf(productId, 1), correlationId).status())
                .isEqualTo(201);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<JsonNode> lines = logLinesWith(correlationId);
            assertThat(lines).extracting(line -> line.at("/service/name").asText())
                    .contains("api-gateway", "order-service", "product-service");
            assertThat(lines).extracting(line -> line.path("traceId").asText())
                    .allSatisfy(traceId -> assertThat(traceId).isNotBlank())
                    .containsOnly(lines.get(0).path("traceId").asText());
        });
    }

    private static List<JsonNode> logLinesWith(String correlationId) throws Exception {
        String logs = Platform.compose("logs", "--no-log-prefix", "--since", "2m",
                "api-gateway", "order-service", "product-service");
        List<JsonNode> lines = new ArrayList<>();
        for (String line : logs.split("\\R")) {
            if (line.startsWith("{") && line.contains(correlationId)) {
                JsonNode node = JSON.readTree(line);
                if (correlationId.equals(node.path("correlationId").asText())) {
                    lines.add(node);
                }
            }
        }
        return lines;
    }

}
