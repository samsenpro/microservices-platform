package com.samsenpro.platform.product;

import com.samsenpro.platform.commons.testing.TestJwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles({"test", "chaos"})
class ChaosModeIntegrationTest extends PostgresIntegrationTest {

    private static final String USER = "Bearer " + TestJwts.user(UUID.randomUUID());

    @Autowired
    private MockMvc mvc;

    @AfterEach
    void resetChaos() throws Exception {
        configure("NONE", 0, "0.0");
    }

    @Test
    void injectsHttp503() throws Exception {
        configure("ERROR_503", 0, "1.0");

        mvc.perform(get("/api/products/1").header("Authorization", USER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CHAOS_SIMULATED_FAILURE"));
    }

    @Test
    void injectsHttp500() throws Exception {
        configure("ERROR_500", 0, "1.0");

        mvc.perform(get("/api/products/1").header("Authorization", USER))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void injectsLatencyButStillAnswers() throws Exception {
        configure("DELAY", 300, "1.0");

        long start = System.nanoTime();
        mvc.perform(get("/api/products").header("Authorization", USER)).andExpect(status().isOk());

        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(300);
    }

    @Test
    void onlyReachableFromInsideTheContainer() throws Exception {
        mvc.perform(post("/internal/chaos").with(request -> {
                            request.setRemoteAddr("172.18.0.9");
                            return request;
                        })
                        .param("mode", "ERROR_503"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsOutOfRangeSettings() throws Exception {
        mvc.perform(post("/internal/chaos").param("mode", "ERROR_503").param("failureRate", "1.5"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/chaos").param("mode", "EXPLODE"))
                .andExpect(status().isBadRequest());
    }

    private void configure(String mode, long delayMs, String failureRate) throws Exception {
        mvc.perform(post("/internal/chaos").param("mode", mode).param("delayMs", String.valueOf(delayMs))
                        .param("failureRate", failureRate))
                .andExpect(status().isOk());
    }
}
