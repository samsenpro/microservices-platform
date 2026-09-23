package com.samsenpro.platform.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Arranca el Config Server real sobre el config-repo/ del proyecto. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "CONFIG_REPO_LOCATION=file:../config-repo/",
        "CONFIG_SERVER_USERNAME=config-test",
        "CONFIG_SERVER_PASSWORD=config-test-password"
})
class ConfigServerIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void servesServiceSpecificProfileAndSharedConfigurationInPrecedenceOrder() {
        ResponseEntity<JsonNode> response = authenticated().getForEntity("/order-service/docker", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> sources = new ArrayList<>();
        response.getBody().get("propertySources").forEach(source -> sources.add(source.get("name").asText()));
        assertThat(sources).hasSize(3);
        assertThat(sources.get(0)).endsWith("application-docker.yml");
        assertThat(sources.get(1)).endsWith("order-service.yml");
        assertThat(sources.get(2)).endsWith("application.yml");
    }

    @Test
    void timeoutsAndResilienceSettingsComeFromTheCentralRepository() {
        JsonNode source = firstSourceEndingWith("/order-service/default", "order-service.yml");

        assertThat(source.get("clients.product-service.read-timeout").asText()).isEqualTo("2s");
        assertThat(source.get("resilience4j.retry.instances.productService.max-attempts").asInt()).isEqualTo(3);
        assertThat(source.get("clients.product-service.base-url").asText()).isEqualTo("http://product-service");
    }

    @Test
    void secretsAreServedAsUnresolvedPlaceholders() {
        JsonNode shared = firstSourceEndingWith("/user-service/default", "application.yml");
        JsonNode users = firstSourceEndingWith("/user-service/default", "user-service.yml");

        assertThat(shared.get("security.jwt.secret").asText()).isEqualTo("${JWT_SECRET}");
        assertThat(users.get("spring.datasource.password").asText()).isEqualTo("${DB_PASSWORD}");
    }

    @Test
    void configurationRequiresCredentials() {
        assertThat(rest.getForEntity("/user-service/default", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.withBasicAuth("config-test", "wrong").getForEntity("/user-service/default", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void healthIsPublicForContainerHealthChecks() {
        ResponseEntity<JsonNode> health = rest.getForEntity("/actuator/health", JsonNode.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().get("status").asText()).isEqualTo("UP");
    }

    private JsonNode firstSourceEndingWith(String path, String suffix) {
        JsonNode body = authenticated().getForObject(path, JsonNode.class);
        for (JsonNode source : body.get("propertySources")) {
            if (source.get("name").asText().endsWith(suffix)) {
                return source.get("source");
            }
        }
        throw new AssertionError("No property source ending with " + suffix);
    }

    private TestRestTemplate authenticated() {
        return rest.withBasicAuth("config-test", "config-test-password");
    }
}
