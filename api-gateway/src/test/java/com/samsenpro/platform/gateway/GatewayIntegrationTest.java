package com.samsenpro.platform.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.samsenpro.platform.commons.testing.TestJwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway real (rutas de config-repo/api-gateway.yml) delante de microservicios simulados con WireMock.
 * user-service y product-service están "registrados" en discovery; order-service NO (simula que no
 * hay ninguna instancia UP).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s")
class GatewayIntegrationTest {

    private static final WireMockServer SERVICES = new WireMockServer(options().dynamicPort());

    static {
        SERVICES.start();
    }

    @DynamicPropertySource
    static void discovery(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri", SERVICES::baseUrl);
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri", SERVICES::baseUrl);
    }

    @Autowired
    private WebTestClient client;

    @BeforeEach
    void reset() {
        SERVICES.resetAll();
        client = client.mutate().responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void routesAuthenticatedRequestsThroughServiceDiscoveryKeepingTheToken() {
        String token = TestJwts.user(UUID.randomUUID());
        SERVICES.stubFor(get("/api/products/7").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":7}")));

        client.get().uri("/api/products/7").header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(7);

        // El servicio recibe el mismo JWT (para volver a validarlo) y el correlation ID generado
        SERVICES.verify(getRequestedFor(urlEqualTo("/api/products/7"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .withHeader("X-Correlation-ID", matching("[0-9a-f-]{36}")));
    }

    @Test
    void rejectsMissingOrInvalidTokensAtTheEdge() {
        client.get().uri("/api/products/1").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.correlationId").isNotEmpty();

        String expired = TestJwts.token(TestJwts.SECRET, TestJwts.ISSUER, UUID.randomUUID().toString(), "USER",
                Duration.ofMinutes(-5));
        client.get().uri("/api/products/1").header("Authorization", "Bearer " + expired).exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_TOKEN");

        String forged = TestJwts.token("forged-secret-forged-secret-forged-secret-000", TestJwts.ISSUER,
                UUID.randomUUID().toString(), "ADMIN", Duration.ofMinutes(5));
        client.get().uri("/api/orders").header("Authorization", "Bearer " + forged).exchange()
                .expectStatus().isUnauthorized();

        // Nada de lo anterior llegó a los microservicios
        assertThat(SERVICES.getAllServeEvents()).isEmpty();
    }

    @Test
    void preservesAValidClientCorrelationIdAndReplacesAnInvalidOne() {
        String token = TestJwts.user(UUID.randomUUID());
        SERVICES.stubFor(get("/api/products").willReturn(aResponse().withStatus(200).withBody("[]")));

        client.get().uri("/api/products").header("Authorization", "Bearer " + token)
                .header("X-Correlation-ID", "client-trace-42")
                .exchange()
                .expectHeader().valueEquals("X-Correlation-ID", "client-trace-42");
        SERVICES.verify(getRequestedFor(urlEqualTo("/api/products"))
                .withHeader("X-Correlation-ID", equalTo("client-trace-42")));

        String generated = client.get().uri("/api/products").header("Authorization", "Bearer " + token)
                .header("X-Correlation-ID", "bad value; with <script> and spaces")
                .exchange()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-ID");
        assertThat(generated).matches("[0-9a-f-]{36}");
    }

    @Test
    void loginIsPublicButRateLimitedPerClientIp() {
        SERVICES.stubFor(post("/api/users/login").willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json").withBody("{\"code\":\"INVALID_CREDENTIALS\"}")));

        int forwarded = 0;
        boolean limited = false;
        for (int attempt = 0; attempt < 4 && !limited; attempt++) {
            var result = client.post().uri("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"username\":\"alice\",\"password\":\"guess-" + attempt + "\"}")
                    .exchange().returnResult(String.class);
            if (result.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                limited = true;
                assertThat(result.getResponseHeaders().getFirst("Retry-After")).isNotBlank();
                assertThat(result.getResponseBody().blockFirst()).contains("RATE_LIMIT_EXCEEDED");
            } else {
                assertThat(result.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(result.getResponseHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("3");
                forwarded++;
            }
        }

        assertThat(limited).as("the 4th login in the same minute must be rejected").isTrue();
        assertThat(forwarded).isLessThanOrEqualTo(3);
        SERVICES.verify(forwarded, postRequestedFor(urlEqualTo("/api/users/login")));
    }

    @Test
    void authenticatedApiIsRateLimitedPerUserNotGlobally() {
        String greedy = TestJwts.user(UUID.randomUUID());
        String polite = TestJwts.user(UUID.randomUUID());
        SERVICES.stubFor(get("/api/products").willReturn(aResponse().withStatus(200).withBody("[]")));

        for (int i = 0; i < 5; i++) {
            client.get().uri("/api/products").header("Authorization", "Bearer " + greedy).exchange()
                    .expectStatus().isOk();
        }
        client.get().uri("/api/products").header("Authorization", "Bearer " + greedy).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        client.get().uri("/api/products").header("Authorization", "Bearer " + polite).exchange()
                .expectStatus().isOk();
    }

    @Test
    void serviceWithoutHealthyInstancesAnswers503() {
        client.get().uri("/api/orders").header("Authorization", "Bearer " + TestJwts.user(UUID.randomUUID()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.path").isEqualTo("/api/orders");
    }

    @Test
    void slowServiceAnswers504InsteadOfHangingTheClient() {
        SERVICES.stubFor(get("/api/products/99").willReturn(aResponse().withStatus(200).withFixedDelay(3_000)));

        client.get().uri("/api/products/99").header("Authorization", "Bearer " + TestJwts.user(UUID.randomUUID()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
                .expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_TIMEOUT");
    }

    @Test
    void downstreamErrorsPassThroughUntouched() {
        SERVICES.stubFor(get("/api/products/404").willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json").withBody("{\"code\":\"PRODUCT_NOT_FOUND\"}")));

        client.get().uri("/api/products/404").header("Authorization", "Bearer " + TestJwts.user(UUID.randomUUID()))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void unknownRoutesAreNotFound() {
        client.get().uri("/internal/chaos").header("Authorization", "Bearer " + TestJwts.admin(UUID.randomUUID()))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void healthIsPublicButMetricsRequireAdmin() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("/actuator/metrics").header("Authorization", "Bearer " + TestJwts.user(UUID.randomUUID()))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/actuator/metrics").header("Authorization", "Bearer " + TestJwts.admin(UUID.randomUUID()))
                .exchange().expectStatus().isOk();
    }
}
