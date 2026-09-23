package com.samsenpro.platform.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsenpro.platform.commons.testing.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductApiIntegrationTest extends PostgresIntegrationTest {

    private static final String ADMIN = "Bearer " + TestJwts.admin(UUID.randomUUID());
    private static final String USER = "Bearer " + TestJwts.user(UUID.randomUUID());

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    @Test
    void adminCreatesAndAnyAuthenticatedUserReads() throws Exception {
        long id = createProduct("Keyboard", "89.90", 25);

        mvc.perform(get("/api/products/" + id).header("Authorization", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Keyboard"))
                .andExpect(jsonPath("$.price").value(89.90))
                .andExpect(jsonPath("$.stock").value(25))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void writeOperationsRequireAdminRole() throws Exception {
        long id = createProduct("Mouse", "19.99", 10);
        String body = productBody("Mouse", "29.99", 10);

        mvc.perform(post("/api/products").header("Authorization", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(put("/api/products/" + id).header("Authorization", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/products/" + id).header("Authorization", USER))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestsAreRejectedEvenIfTheGatewayWasBypassed() throws Exception {
        mvc.perform(get("/api/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void adminUpdatesAProduct() throws Exception {
        long id = createProduct("Monitor", "199.00", 5);

        mvc.perform(put("/api/products/" + id).header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(productBody("Monitor 27\"", "189.00", 7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Monitor 27\""))
                .andExpect(jsonPath("$.price").value(189.00))
                .andExpect(jsonPath("$.stock").value(7));
    }

    @Test
    void deleteIsLogicalAndHidesTheProductFromUsersOnly() throws Exception {
        long id = createProduct("Discontinued", "5.00", 1);

        mvc.perform(delete("/api/products/" + id).header("Authorization", ADMIN))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/products/" + id).header("Authorization", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/products?size=100").header("Authorization", USER))
                .andExpect(jsonPath("$.content[*].id", not(hasItem((int) id))));
        mvc.perform(get("/api/products?size=100").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.content[*].id", hasItem((int) id)));
    }

    @Test
    void invalidProductIsRejectedWithFieldErrors() throws Exception {
        mvc.perform(post("/api/products").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(productBody("", "-1", -3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("price")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("stock")));
    }

    @Test
    void unknownProductReturns404WithCorrelationId() throws Exception {
        mvc.perform(get("/api/products/999999").header("Authorization", USER).header("X-Correlation-ID", "corr-404"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-ID", "corr-404"))
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value("corr-404"));
    }

    @Test
    void invalidSortPropertyIsABadRequestNotAServerError() throws Exception {
        mvc.perform(get("/api/products?sort=doesNotExist").header("Authorization", USER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chaosEndpointDoesNotExistWithoutTheChaosProfile() throws Exception {
        mvc.perform(get("/internal/chaos")).andExpect(status().isNotFound());
    }

    private long createProduct(String name, String price, int stock) throws Exception {
        ResultActions result = mvc.perform(post("/api/products").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(productBody(name, price, stock)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private String productBody(String name, String price, int stock) throws Exception {
        return json.writeValueAsString(Map.of("name", name, "description", "test product",
                "price", new java.math.BigDecimal(price), "stock", stock));
    }
}
