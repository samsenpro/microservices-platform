package com.samsenpro.platform.order;

import com.github.tomakehurst.wiremock.http.Fault;
import com.samsenpro.platform.commons.testing.TestJwts;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Escenarios de fallo de product-service y respuesta de cada patrón (valores en application-test.yml):
 * timeout 500 ms, 3 intentos con backoff 50/100 ms, circuito con ventana de 6 y mínimo 4 llamadas,
 * llamada lenta a partir de 300 ms, bulkhead de 2 llamadas concurrentes.
 */
class ResilienceIntegrationTest extends ProductServiceStubIntegrationTest {

    private final String token = TestJwts.user(UUID.randomUUID());

    @Test
    void propagatesUserTokenAndCorrelationIdToProductService() throws Exception {
        stubProduct(1, "10.00", 5, true);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Correlation-ID", "corr-propagation-1")
                        .contentType("application/json")
                        .content("{\"items\":[{\"productId\":1,\"quantity\":2}]}"))
                .andExpect(status().isCreated());

        PRODUCT_SERVICE.verify(getRequestedFor(urlEqualTo("/api/products/1"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .withHeader("X-Correlation-ID", equalTo("corr-propagation-1")));
    }

    @Test
    void transient503IsRetriedUntilItSucceeds() throws Exception {
        PRODUCT_SERVICE.stubFor(productRequest(2).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        PRODUCT_SERVICE.stubFor(productRequest(2).inScenario("flaky").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        PRODUCT_SERVICE.stubFor(productRequest(2).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(productResponse(2, "20.00", 5, true)));

        createOrder(token, 2, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(20.00));

        assertThat(productCalls()).isEqualTo(3);
    }

    @Test
    void persistent503EndsInAControlledErrorAfterMaxAttempts() throws Exception {
        PRODUCT_SERVICE.stubFor(productRequest(3).willReturn(aResponse().withStatus(503)));

        createOrder(token, 3, 1)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Product service is temporarily unavailable"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        assertThat(productCalls()).isEqualTo(3);
    }

    @Test
    void permanentErrorsAreNotRetried() throws Exception {
        PRODUCT_SERVICE.stubFor(productRequest(4).willReturn(aResponse().withStatus(404)));
        createOrder(token, 4, 1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        assertThat(productCalls()).isEqualTo(1);

        PRODUCT_SERVICE.resetRequests();
        PRODUCT_SERVICE.stubFor(productRequest(5).willReturn(aResponse().withStatus(403)));
        createOrder(token, 5, 1)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_REJECTED_REQUEST"));
        assertThat(productCalls()).isEqualTo(1);
    }

    @Test
    void http500IsReportedAsDependencyErrorWithoutRetry() throws Exception {
        PRODUCT_SERVICE.stubFor(productRequest(6).willReturn(aResponse().withStatus(500)));

        createOrder(token, 6, 1)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_ERROR"));

        assertThat(productCalls()).isEqualTo(1);
    }

    @Test
    void slowResponsesHitTheTimeoutInsteadOfBlockingThreads() throws Exception {
        PRODUCT_SERVICE.stubFor(productRequest(7).willReturn(productResponse(7, "10.00", 5, true)
                .withFixedDelay(3_000)));

        long start = System.nanoTime();
        createOrder(token, 7, 1)
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_TIMEOUT"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 3 intentos de ~500 ms + backoff: muy lejos de los 3 x 3 s que tardaría sin timeout
        assertThat(productCalls()).isEqualTo(3);
        assertThat(elapsedMs).isLessThan(2_500);
    }

    @Test
    void connectionResetIsTreatedAsTransient() throws Exception {
        PRODUCT_SERVICE.stubFor(productRequest(8).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        createOrder(token, 8, 1)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));

        assertThat(productCalls()).isEqualTo(3);
    }

    @Test
    void circuitOpensFailsFastAndRecoversThroughHalfOpen() throws Exception {
        PRODUCT_SERVICE.stubFor(productRequest(9).willReturn(aResponse().withStatus(500)));

        // CLOSED: 4 llamadas fallidas (mínimo para evaluar) con 100 % de fallos -> OPEN
        for (int i = 0; i < 4; i++) {
            createOrder(token, 9, 1).andExpect(status().isBadGateway());
        }
        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // OPEN: se falla al instante, sin llamar a product-service
        int callsBefore = productCalls();
        createOrder(token, 9, 1)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));
        assertThat(productCalls()).isEqualTo(callsBefore);

        // Tras wait-duration-in-open-state (1 s) pasa solo a HALF_OPEN
        await().atMost(Duration.ofSeconds(3))
                .until(() -> circuitBreaker().getState() == CircuitBreaker.State.HALF_OPEN);

        // HALF_OPEN: las llamadas de prueba van bien -> CLOSED
        stubProduct(9, "15.00", 10, true);
        createOrder(token, 9, 1).andExpect(status().isCreated());
        createOrder(token, 9, 1).andExpect(status().isCreated());
        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void failedProbeInHalfOpenReopensTheCircuit() throws Exception {
        PRODUCT_SERVICE.stubFor(productRequest(10).willReturn(aResponse().withStatus(500)));
        for (int i = 0; i < 4; i++) {
            createOrder(token, 10, 1);
        }
        await().atMost(Duration.ofSeconds(3))
                .until(() -> circuitBreaker().getState() == CircuitBreaker.State.HALF_OPEN);

        createOrder(token, 10, 1).andExpect(status().isBadGateway());
        createOrder(token, 10, 1).andExpect(status().isBadGateway());

        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void consistentlySlowDependencyOpensTheCircuitEvenWithoutErrors() throws Exception {
        // 400 ms: por debajo del timeout (500 ms) pero por encima del umbral de llamada lenta (300 ms)
        PRODUCT_SERVICE.stubFor(productRequest(11).willReturn(productResponse(11, "10.00", 100, true)
                .withFixedDelay(400)));

        for (int i = 0; i < 4; i++) {
            createOrder(token, 11, 1).andExpect(status().isCreated());
        }

        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        createOrder(token, 11, 1).andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void bulkheadRejectsCallsBeyondTheConcurrencyLimit() throws Exception {
        PRODUCT_SERVICE.stubFor(productRequest(12).willReturn(productResponse(12, "10.00", 100, true)
                .withFixedDelay(400)));

        List<Callable<MvcResult>> requests = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            requests.add(() -> createOrder(token, 12, 1).andReturn());
        }
        List<String> codes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            for (Future<MvcResult> result : pool.invokeAll(requests)) {
                String body = result.get().getResponse().getContentAsString();
                codes.add(result.get().getResponse().getStatus() + (body.contains("PRODUCT_SERVICE_BUSY") ? "-BUSY" : ""));
            }
        }

        // Como máximo 2 llamadas simultáneas a product-service; el resto se rechaza al instante
        assertThat(codes).contains("503-BUSY");
        assertThat(codes).filteredOn("201"::equals).hasSizeBetween(1, 2);
        assertThat(productCalls()).isLessThanOrEqualTo(2);
    }
}
