package com.samsenpro.platform.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Acceso a la plataforma levantada con docker compose. Todas las llamadas HTTP van al API Gateway; los
 * únicos accesos "internos" (activar el modo chaos, leer el estado del circuit breaker o los logs) se hacen
 * con docker compose exec/logs, como lo haría un operador.
 */
final class Platform {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String GATEWAY = setting("E2E_GATEWAY_URL", "http://localhost:" + setting("GATEWAY_PORT", "8090"));

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static String adminToken;
    private static String userToken;
    private static String otherUserToken;

    private Platform() {
    }

    // ------------------------------------------------------------------ HTTP a través del gateway

    record Response(int status, JsonNode body, HttpResponse<String> raw) {

        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        List<String> headers(String name) {
            return raw.headers().allValues(name);
        }
    }

    static Response get(String path, String token) {
        return send(request(path, token, null).GET().build());
    }

    static Response post(String path, String token, Object body) {
        return send(request(path, token, null).POST(jsonBody(body)).build());
    }

    static Response post(String path, String token, Object body, String correlationId) {
        return send(request(path, token, correlationId).POST(jsonBody(body)).build());
    }

    private static HttpRequest.Builder request(String path, String token, String correlationId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(GATEWAY + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (correlationId != null) {
            builder.header("X-Correlation-ID", correlationId);
        }
        return builder;
    }

    private static HttpRequest.BodyPublisher jsonBody(Object body) {
        try {
            return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank()
                    ? JSON.nullNode() : JSON.readTree(response.body());
            return new Response(response.statusCode(), body, response);
        } catch (IOException e) {
            throw new IllegalStateException("Is the platform running? (docker compose up -d) " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ usuarios

    /** Registra un usuario nuevo y devuelve su JWT (2 llamadas al endpoint de autenticación). */
    static String newUserToken(String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Response registered = post("/api/users/register", null,
                Map.of("username", username, "email", username + "@e2e.test", "password", "E2e-Passw0rd!"));
        if (registered.status() != 201) {
            throw new IllegalStateException("register failed: " + registered.status() + " " + registered.body());
        }
        return login(username, "E2e-Passw0rd!");
    }

    /** Usuario compartido por los tests: el login está limitado por IP en el gateway (10/min por defecto). */
    static synchronized String userToken() {
        if (userToken == null) {
            userToken = newUserToken("e2e-buyer");
        }
        return userToken;
    }

    static synchronized String otherUserToken() {
        if (otherUserToken == null) {
            otherUserToken = newUserToken("e2e-other");
        }
        return otherUserToken;
    }

    static synchronized String adminToken() {
        if (adminToken == null) {
            adminToken = login(setting("ADMIN_USERNAME", "admin"), setting("ADMIN_PASSWORD", null));
        }
        return adminToken;
    }

    static String login(String username, String password) {
        Response response = post("/api/users/login", null, Map.of("username", username, "password", password));
        if (response.status() != 200) {
            throw new IllegalStateException("login failed for " + username + ": " + response.status() + " " + response.body());
        }
        return response.body().get("accessToken").asText();
    }

    static long createProduct(String name, String price, int stock) {
        Response response = post("/api/products", adminToken(),
                Map.of("name", name, "description", "e2e", "price", new java.math.BigDecimal(price), "stock", stock));
        if (response.status() != 201) {
            throw new IllegalStateException("create product failed: " + response.status() + " " + response.body());
        }
        return response.body().get("id").asLong();
    }

    static Map<String, Object> orderOf(long productId, int quantity) {
        return Map.of("items", List.of(Map.of("productId", productId, "quantity", quantity)));
    }

    // ------------------------------------------------------------------ operaciones internas (docker compose)

    static String compose(String... args) {
        List<String> command = new java.util.ArrayList<>(List.of("docker", "compose"));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Timeout running " + command);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(command + " failed: " + output);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Variable de entorno, propiedad de sistema o, en último lugar, el .env de la raíz del proyecto. */
    static String setting(String name, String defaultValue) {
        String value = System.getProperty(name, System.getenv(name));
        if (value == null) {
            value = dotEnv().get(name);
        }
        if (value == null && defaultValue == null) {
            throw new IllegalStateException(name + " is not defined (environment or .env)");
        }
        return value != null ? value : defaultValue;
    }

    private static Map<String, String> dotEnv() {
        Map<String, String> values = new HashMap<>();
        Path file = Path.of(".env");
        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file)) {
                    int eq = line.indexOf('=');
                    if (!line.isBlank() && !line.startsWith("#") && eq > 0) {
                        values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return values;
    }
}
