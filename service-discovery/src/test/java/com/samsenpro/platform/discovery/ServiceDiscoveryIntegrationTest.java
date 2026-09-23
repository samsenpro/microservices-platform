package com.samsenpro.platform.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceDiscoveryIntegrationTest {

    private static final String REGISTRATION = """
            {"instance": {
              "instanceId": "test-host:order-service:8093",
              "hostName": "test-host",
              "app": "ORDER-SERVICE",
              "ipAddr": "10.0.0.5",
              "status": "UP",
              "port": {"$": 8093, "@enabled": "true"},
              "dataCenterInfo": {
                "@class": "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
                "name": "MyOwn"
              }
            }}
            """;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void registeredInstancesAreVisibleToAuthenticatedClients() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Void> registration = eureka().exchange("/eureka/apps/ORDER-SERVICE", HttpMethod.POST,
                new HttpEntity<>(REGISTRATION, headers), Void.class);
        assertThat(registration.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        HttpHeaders accept = new HttpHeaders();
        accept.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> apps = eureka().exchange("/eureka/apps/ORDER-SERVICE", HttpMethod.GET,
                new HttpEntity<>(accept), String.class);
        assertThat(apps.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(apps.getBody()).contains("test-host:order-service:8093").contains("\"UP\"");
    }

    @Test
    void anonymousClientsCannotRegisterOrReadTheRegistry() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        assertThat(rest.exchange("/eureka/apps/FAKE-SERVICE", HttpMethod.POST,
                new HttpEntity<>(REGISTRATION, headers), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/eureka/apps", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void healthIsPublic() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private TestRestTemplate eureka() {
        return rest.withBasicAuth("eureka-test", "eureka-test-password");
    }
}
