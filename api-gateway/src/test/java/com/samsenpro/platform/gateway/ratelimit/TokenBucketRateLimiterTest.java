package com.samsenpro.platform.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketRateLimiterTest {

    private final AtomicLong now = new AtomicLong();
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(now::get);

    @Test
    void allowsABurstUpToTheLimitThenRejects() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("login|ip:1.2.3.4", 5, Duration.ofMinutes(1)).allowed()).isTrue();
        }

        TokenBucketRateLimiter.Decision rejected = limiter.tryConsume("login|ip:1.2.3.4", 5, Duration.ofMinutes(1));

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
        // 5 por minuto = 1 token cada 12 s
        assertThat(rejected.retryAfterSeconds()).isEqualTo(12);
    }

    @Test
    void refillsContinuouslyOverTime() {
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume("k", 5, Duration.ofMinutes(1));
        }
        now.addAndGet(Duration.ofSeconds(12).toNanos());

        assertThat(limiter.tryConsume("k", 5, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(limiter.tryConsume("k", 5, Duration.ofMinutes(1)).allowed()).isFalse();
    }

    @Test
    void clientsDoNotShareBuckets() {
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume("login|ip:10.0.0.1", 3, Duration.ofMinutes(1));
        }

        assertThat(limiter.tryConsume("login|ip:10.0.0.1", 3, Duration.ofMinutes(1)).allowed()).isFalse();
        assertThat(limiter.tryConsume("login|ip:10.0.0.2", 3, Duration.ofMinutes(1)).allowed()).isTrue();
    }

    @Test
    void idleBucketsAreEvicted() {
        limiter.tryConsume("old-client", 3, Duration.ofMinutes(1));
        now.addAndGet(Duration.ofMinutes(11).toNanos());
        limiter.tryConsume("new-client", 3, Duration.ofMinutes(1));

        limiter.evictIdleBuckets();

        assertThat(limiter.trackedClients()).isEqualTo(1);
    }
}
