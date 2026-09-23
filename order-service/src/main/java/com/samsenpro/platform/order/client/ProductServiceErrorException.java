package com.samsenpro.platform.order.client;

/**
 * product-service respondió con un error de servidor no transitorio (por ejemplo 500). Cuenta como fallo
 * para el Circuit Breaker, pero NO se reintenta: repetir la misma petición produciría el mismo error.
 */
public final class ProductServiceErrorException extends ProductServiceFailure {

    private final int status;

    public ProductServiceErrorException(int status) {
        super("Product service answered HTTP " + status, null);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
