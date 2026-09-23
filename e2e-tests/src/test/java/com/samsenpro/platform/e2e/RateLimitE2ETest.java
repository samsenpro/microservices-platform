package com.samsenpro.platform.e2e;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Va el último: agota el cupo de login de esta IP durante el periodo configurado. */
@Order(4)
class RateLimitE2ETest {

    @Test
    void bruteForceLoginIsThrottledPerIp() {
        int limit = Integer.parseInt(Platform.setting("RATE_LIMIT_AUTH_REQUESTS", "10"));
        Platform.Response last = null;
        int attempts = 0;
        while (attempts <= limit) {
            attempts++;
            last = Platform.post("/api/users/login", null, Map.of("username", "admin", "password", "guess-" + attempts));
            if (last.status() == 429) {
                break;
            }
            assertThat(last.status()).isEqualTo(401);
        }

        assertThat(last.status()).as("login must be throttled after %d attempts", limit).isEqualTo(429);
        assertThat(last.body().get("code").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(last.header("Retry-After")).isNotBlank();
        assertThat(last.header("X-RateLimit-Limit")).isEqualTo(String.valueOf(limit));
    }
}
