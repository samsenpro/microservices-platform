package com.samsenpro.platform.gateway.web;

import io.micrometer.tracing.handler.TracingObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * El correlation ID nace en el gateway: si el cliente no envía uno válido se genera. Se añade a la
 * petición que va al microservicio (que lo propaga a su vez) y a la respuesta. Es un WebFilter (no un
 * GlobalFilter) para ejecutarse antes que Spring Security: también los 401 y 429 lo llevan.
 *
 * <p>Además escribe una línea de log de acceso por petición, sin cabeceras (nunca el JWT).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    public static final String HEADER = "X-Correlation-ID";
    private static final String ATTRIBUTE = CorrelationIdWebFilter.class.getName() + ".id";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdWebFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String candidate = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = candidate != null && VALID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, correlationId))
                .build();
        ServerWebExchange mutated = exchange.mutate().request(request).build();
        mutated.getAttributes().put(ATTRIBUTE, correlationId);
        // set() justo antes de enviar la respuesta: sustituye la copia que devuelve el microservicio y
        // evita la cabecera duplicada
        mutated.getResponse().beforeCommit(() -> {
            mutated.getResponse().getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });

        long start = System.nanoTime();
        return chain.filter(mutated)
                .doFinally(signal -> logAccess(mutated, correlationId, (System.nanoTime() - start) / 1_000_000));
    }

    public static String correlationId(ServerWebExchange exchange) {
        return exchange.getAttribute(ATTRIBUTE);
    }

    private static void logAccess(ServerWebExchange exchange, String correlationId, long durationMs) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/actuator")) {
            return;
        }
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        try (MDC.MDCCloseable ignoredCorrelation = MDC.putCloseable("correlationId", correlationId);
             MDC.MDCCloseable ignoredTrace = MDC.putCloseable("traceId", traceId(exchange))) {
            log.info("{} {} -> {} ({} ms)", exchange.getRequest().getMethod(), path,
                    status != null ? status.value() : "-", durationMs);
        }
    }

    /** traceId de la observación HTTP de la petición: el mismo que llega a los servicios en traceparent. */
    private static String traceId(ServerWebExchange exchange) {
        return ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .map(context -> context.<TracingObservationHandler.TracingContext>get(
                        TracingObservationHandler.TracingContext.class))
                .map(TracingObservationHandler.TracingContext::getSpan)
                .map(span -> span.context().traceId())
                .orElse(null);
    }
}
