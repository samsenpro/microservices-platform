package com.samsenpro.platform.gateway.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token bucket en memoria, uno por (ruta, cliente). Cada bucket admite ráfagas de hasta {@code limit}
 * peticiones y se rellena de forma continua a {@code limit / period}.
 *
 * <p>Límite conocido: el estado vive en cada instancia del gateway. Con varias réplicas el límite efectivo
 * se multiplica por el número de réplicas; en producción se usaría un almacén compartido (Redis, con el
 * RedisRateLimiter de Spring Cloud Gateway).
 */
@Component
public class TokenBucketRateLimiter {

    private static final Duration IDLE_EVICTION = Duration.ofMinutes(10);

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;

    public TokenBucketRateLimiter() {
        this(System::nanoTime);
    }

    TokenBucketRateLimiter(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    public Decision tryConsume(String key, int limit, Duration period) {
        long now = nanoClock.getAsLong();
        return buckets.computeIfAbsent(key, k -> new Bucket(limit, now)).tryConsume(limit, period, now);
    }

    /** Evita que la memoria crezca con clientes que ya no envían peticiones. */
    @Scheduled(fixedDelay = 60_000)
    void evictIdleBuckets() {
        long now = nanoClock.getAsLong();
        buckets.entrySet().removeIf(entry -> now - entry.getValue().lastAccess() > IDLE_EVICTION.toNanos());
    }

    int trackedClients() {
        return buckets.size();
    }

    /**
     * @param allowed           si la petición puede continuar
     * @param remaining         peticiones disponibles tras esta
     * @param retryAfterSeconds segundos hasta que haya un token disponible (0 si se permitió)
     */
    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }

    private static final class Bucket {

        private static final double EPSILON = 1e-6;

        private double tokens;
        private long lastRefill;
        private long lastAccess;

        Bucket(int limit, long now) {
            this.tokens = limit;
            this.lastRefill = now;
            this.lastAccess = now;
        }

        synchronized Decision tryConsume(int limit, Duration period, long now) {
            double tokensPerNano = (double) limit / period.toNanos();
            tokens = Math.min(limit, tokens + (now - lastRefill) * tokensPerNano);
            lastRefill = now;
            lastAccess = now;
            // EPSILON absorbe el error de redondeo de la aritmética en coma flotante
            if (tokens >= 1 - EPSILON) {
                tokens = Math.max(0, tokens - 1);
                return new Decision(true, (long) Math.floor(tokens + EPSILON), 0);
            }
            double secondsUntilToken = (1 - tokens) / tokensPerNano / 1_000_000_000d;
            return new Decision(false, 0, Math.max(1, (long) Math.ceil(secondsUntilToken - EPSILON)));
        }

        synchronized long lastAccess() {
            return lastAccess;
        }
    }
}
