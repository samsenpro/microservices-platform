package com.samsenpro.platform.e2e;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Order(2)
class SecurityE2ETest {

    @Test
    void requestsWithoutTokenAreRejectedByTheGateway() {
        Platform.Response response = Platform.get("/api/orders", null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body().get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(response.body().get("correlationId").asText()).isNotBlank();
    }

    @Test
    void tamperedTokensAreRejected() {
        String token = Platform.userToken();
        String[] parts = token.split("\\.");
        // Se cambia el payload (por ejemplo, para intentar ser ADMIN) sin poder volver a firmarlo
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];

        Platform.Response response = Platform.get("/api/users/me", tampered);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body().get("code").asText()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void rolesAreEnforced() {
        Platform.Response userCreatesProduct = Platform.post("/api/products", Platform.userToken(),
                Map.of("name", "Not allowed", "price", 1, "stock", 1));
        assertThat(userCreatesProduct.status()).isEqualTo(403);

        Platform.Response userListsUsers = Platform.get("/api/users", Platform.userToken());
        assertThat(userListsUsers.status()).isEqualTo(403);

        assertThat(Platform.get("/api/users", Platform.adminToken()).status()).isEqualTo(200);
    }

    @Test
    void usersCannotSeeOtherUsersOrders() {
        long productId = Platform.createProduct("E2E private item", "3.00", 5);
        Platform.Response order = Platform.post("/api/orders", Platform.userToken(), Platform.orderOf(productId, 1));
        String orderId = order.body().get("id").asText();

        Platform.Response other = Platform.get("/api/orders/" + orderId, Platform.otherUserToken());

        assertThat(other.status()).isEqualTo(404);
        assertThat(Platform.get("/api/orders/" + orderId, Platform.adminToken()).status()).isEqualTo(200);
    }

    @Test
    void microservicesAreOnlyReachableThroughTheGateway() {
        for (int port : new int[]{8091, 8092, 8093, 8888}) {
            assertThatThrownBy(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("localhost", port), 1_000);
                }
            }).as("port %d must not be published", port).isInstanceOf(IOException.class);
        }
        // El endpoint de simulación de fallos no se expone a través del gateway
        assertThat(Platform.get("/internal/chaos", Platform.adminToken()).status()).isEqualTo(404);
    }
}
