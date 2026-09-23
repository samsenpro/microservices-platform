package com.samsenpro.platform.gateway.ratelimit;

import com.samsenpro.platform.gateway.error.ApiErrorResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * Filtro {@code ClientRateLimit} de las rutas (config-repo/api-gateway.yml). La clave es el usuario del
 * JWT si la petición está autenticada y, si no (login, registro), la IP del cliente.
 *
 * <p>Se usa la IP de la conexión y no X-Forwarded-For: esa cabecera la puede falsificar el cliente. Detrás
 * de un balanceador de confianza se activaría {@code server.forward-headers-strategy}.
 */
@Component
public class ClientRateLimitGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ClientRateLimitGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(ClientRateLimitGatewayFilterFactory.class);

    private final TokenBucketRateLimiter rateLimiter;
    private final ApiErrorResponses errors;

    public ClientRateLimitGatewayFilterFactory(TokenBucketRateLimiter rateLimiter, ApiErrorResponses errors) {
        super(Config.class);
        this.rateLimiter = rateLimiter;
        this.errors = errors;
    }

    @Override
    public GatewayFilter apply(Config config) {
        if (config.getLimit() < 1 || config.getPeriod() == null || config.getPeriod().isZero()) {
            throw new IllegalArgumentException("ClientRateLimit requires limit >= 1 and a positive period");
        }
        return (exchange, chain) -> clientKey(exchange).flatMap(client -> {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routeId = route != null ? route.getId() : "unknown";
            TokenBucketRateLimiter.Decision decision =
                    rateLimiter.tryConsume(routeId + "|" + client, config.getLimit(), config.getPeriod());

            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.set("X-RateLimit-Limit", String.valueOf(config.getLimit()));
            headers.set("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
            if (decision.allowed()) {
                return chain.filter(exchange);
            }
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
            log.warn("Rate limit exceeded on route {} for {}", routeId, client);
            return errors.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                    "Too many requests, retry in " + decision.retryAfterSeconds() + " s");
        });
    }

    private static Mono<String> clientKey(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(principal -> "user:" + principal.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange)));
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "unknown";
    }

    public static class Config {

        /** Peticiones permitidas por periodo (y tamaño máximo de ráfaga). */
        private int limit;
        private Duration period;

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public Duration getPeriod() {
            return period;
        }

        public void setPeriod(Duration period) {
            this.period = period;
        }
    }
}
