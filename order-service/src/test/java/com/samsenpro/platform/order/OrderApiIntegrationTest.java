package com.samsenpro.platform.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsenpro.platform.commons.testing.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderApiIntegrationTest extends ProductServiceStubIntegrationTest {

    private final UUID aliceId = UUID.randomUUID();
    private final String alice = TestJwts.user(aliceId);
    private final String bob = TestJwts.user(UUID.randomUUID());
    private final String admin = TestJwts.admin(UUID.randomUUID());

    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsOrderWithPriceTakenFromProductService() throws Exception {
        stubProduct(100, "12.50", 10, true);
        stubProduct(101, "3.00", 10, true);

        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":100,\"quantity\":2},{\"productId\":101,\"quantity\":1},"
                                + "{\"productId\":100,\"quantity\":1}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].unitPrice").value(12.50))
                .andExpect(jsonPath("$.items[0].subtotal").value(37.50))
                .andExpect(jsonPath("$.total").value(40.50));
    }

    @Test
    void rejectsInactiveProductsAndInsufficientStock() throws Exception {
        stubProduct(102, "5.00", 10, false);
        stubProduct(103, "5.00", 1, true);

        createOrder(alice, 102, 1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"));
        createOrder(alice, 103, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void invalidRequestsNeverReachProductService() throws Exception {
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());

        assertThat(productCalls()).isZero();
    }

    @Test
    void usersOnlySeeTheirOwnOrders() throws Exception {
        String orderId = createdOrderId(alice, 104);

        mvc.perform(get("/api/orders/" + orderId).header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());
        mvc.perform(get("/api/orders/" + orderId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mvc.perform(get("/api/orders/" + orderId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        mvc.perform(get("/api/orders").header("Authorization", "Bearer " + bob))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/orders?size=100").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.content[*].id", hasItem(orderId)));
    }

    @Test
    void ownerCancelsOnlyWhileCreated() throws Exception {
        String orderId = createdOrderId(alice, 105);

        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + alice))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void onlyAdminsDriveTheOrderLifecycle() throws Exception {
        String orderId = createdOrderId(alice, 106);

        mvc.perform(patch("/api/orders/" + orderId + "/status").header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/orders/" + orderId + "/status").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/orders/" + orderId + "/status").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PROCESSING\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/orders/" + orderId + "/status").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void flywayAppliedBothMigrations() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version IN ('1', '2')", Integer.class);
        assertThat(applied).isEqualTo(2);
    }

    private String createdOrderId(String token, long productId) throws Exception {
        stubProduct(productId, "9.99", 50, true);
        String body = createOrder(token, productId, 1).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }
}
