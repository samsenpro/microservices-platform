package com.samsenpro.platform.order;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.samsenpro.platform.order.client.ResilientProductClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * product-service simulado con WireMock y registrado como instancia de "product-service" en el
 * SimpleDiscoveryClient: la llamada real pasa por el RestClient @LoadBalanced, los timeouts y Resilience4j.
 */
public abstract class ProductServiceStubIntegrationTest extends PostgresIntegrationTest {

    protected static final WireMockServer PRODUCT_SERVICE = new WireMockServer(options().dynamicPort());

    static {
        PRODUCT_SERVICE.start();
    }

    @DynamicPropertySource
    static void productServiceInstance(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri",
                () -> "http://localhost:" + PRODUCT_SERVICE.port());
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void resetDependency() {
        PRODUCT_SERVICE.resetAll();
        circuitBreaker().reset();
    }

    protected CircuitBreaker circuitBreaker() {
        return circuitBreakers.circuitBreaker(ResilientProductClient.INSTANCE);
    }

    protected static void stubProduct(long id, String price, int stock, boolean active) {
        PRODUCT_SERVICE.stubFor(productRequest(id).willReturn(productResponse(id, price, stock, active)));
    }

    protected static MappingBuilder productRequest(long id) {
        return get("/api/products/" + id);
    }

    protected static ResponseDefinitionBuilder productResponse(long id, String price, int stock, boolean active) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                {"id": %d, "name": "Product %d", "description": "stub", "price": %s, "stock": %d,
                 "active": %s, "createdAt": "2026-09-23T10:00:00Z", "updatedAt": "2026-09-23T10:00:00Z"}
                """.formatted(id, id, price, stock, active));
    }

    protected static int productCalls() {
        return PRODUCT_SERVICE.countRequestsMatching(getRequestedFor(urlPathMatching("/api/products/.*")).build())
                .getCount();
    }

    protected ResultActions createOrder(String bearerToken, long productId, int quantity) throws Exception {
        return mvc.perform(post("/api/orders")
                .header("Authorization", "Bearer " + bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":%d,\"quantity\":%d}]}".formatted(productId, quantity)));
    }
}
