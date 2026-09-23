package com.samsenpro.platform.order;

import com.samsenpro.platform.commons.testing.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ninguna instancia de product-service registrada (o todas DOWN en Eureka). */
@TestPropertySource(properties = "spring.cloud.discovery.client.simple.instances.unrelated-service[0].uri=http://localhost:1")
class NoProductInstancesIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void noRegisteredInstanceIsAControlledUnavailability() throws Exception {
        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + TestJwts.user(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":1}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));
    }
}
