package com.samsenpro.platform.product;

import com.samsenpro.platform.commons.testing.TestJwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles({"test", "chaos"})
class ChaosModeIntegrationTest extends PostgresIntegrationTest {

    private static final String USER = "Bearer " + TestJwts.user(UUID.randomUUID());

    @Autowired
    private MockMvc mvc;

    @AfterEach
    void resetChaos() throws Exception {
        mvc.perform(delete("/internal/chaos")).andExpect(status().isOk());
    }

    @Test
    void injectsHttp503() throws Exception {
        configure("{\"mode\":\"ERROR_503\",\"delayMs\":0,\"failureRate\":1.0}");

        mvc.perform(get("/api/products/1").header("Authorization", USER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CHAOS_SIMULATED_FAILURE"));
    }

    @Test
    void injectsHttp500() throws Exception {
        configure("{\"mode\":\"ERROR_500\",\"delayMs\":0,\"failureRate\":1.0}");

        mvc.perform(get("/api/products/1").header("Authorization", USER))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void injectsLatencyButStillAnswers() throws Exception {
        configure("{\"mode\":\"DELAY\",\"delayMs\":300,\"failureRate\":1.0}");

        long start = System.nanoTime();
        mvc.perform(get("/api/products").header("Authorization", USER)).andExpect(status().isOk());

        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(300);
    }

    @Test
    void onlyReachableFromInsideTheContainer() throws Exception {
        mvc.perform(put("/internal/chaos").with(request -> {
                            request.setRemoteAddr("172.18.0.9");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"ERROR_503\",\"delayMs\":0,\"failureRate\":1.0}"))
                .andExpect(status().isUnauthorized());
    }

    private void configure(String settings) throws Exception {
        mvc.perform(put("/internal/chaos").contentType(MediaType.APPLICATION_JSON).content(settings))
                .andExpect(status().isOk());
    }
}
