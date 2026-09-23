package com.samsenpro.platform.order.client;

/**
 * Fallo TRANSITORIO: tiene sentido reintentar. Timeout, conexión rechazada, ninguna instancia registrada
 * en Eureka o una respuesta 502/503/504.
 */
public final class ProductServiceUnavailableException extends ProductServiceFailure {

    public enum Reason {
        TIMEOUT,
        CONNECTION_FAILED,
        NO_INSTANCES,
        UNAVAILABLE_RESPONSE
    }

    private final Reason reason;

    public ProductServiceUnavailableException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
