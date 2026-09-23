package com.samsenpro.platform.gateway.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/**
 * Errores producidos en el propio gateway (sin instancias en Eureka, servicio que no responde, ruta
 * inexistente...) en el mismo formato JSON que los servicios y sin stack traces. Las respuestas de error
 * de los microservicios pasan tal cual: este handler no las toca.
 */
@Component
@Order(-2)
class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ApiErrorResponses errors;

    GatewayErrorHandler(ApiErrorResponses errors) {
        this.errors = errors;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        String path = exchange.getRequest().getPath().value();
        if (ex instanceof NotFoundException) {
            // Spring Cloud Gateway no encontró ninguna instancia UP del servicio en Eureka
            log.warn("No available instance for {}: {}", path, ((NotFoundException) ex).getReason());
            return errors.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "The service is temporarily unavailable");
        }
        if (ex instanceof ConnectException || ex.getCause() instanceof ConnectException) {
            log.warn("Cannot connect to upstream service for {}: {}", path, ex.getMessage());
            return errors.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "The service is temporarily unavailable");
        }
        if (ex instanceof TimeoutException || isStatus(ex, HttpStatus.GATEWAY_TIMEOUT)) {
            log.warn("Upstream service timed out for {}", path);
            return errors.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT",
                    "The service did not respond in time");
        }
        if (ex instanceof ResponseStatusException statusException && statusException.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.valueOf(statusException.getStatusCode().value());
            return errors.write(exchange, status, status.name(), status.getReasonPhrase());
        }
        log.error("Unexpected gateway error for {} {}", exchange.getRequest().getMethod(), path, ex);
        return errors.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected gateway error");
    }

    private static boolean isStatus(Throwable ex, HttpStatus status) {
        return ex instanceof ResponseStatusException rse && rse.getStatusCode().value() == status.value();
    }
}
