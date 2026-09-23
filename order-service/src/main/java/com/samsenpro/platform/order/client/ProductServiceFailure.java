package com.samsenpro.platform.order.client;

/**
 * Fallo de la dependencia product-service (no del negocio). Es lo único que el Circuit Breaker cuenta como
 * fallo: un 404 o un 422 son respuestas correctas de un servicio sano.
 */
public abstract sealed class ProductServiceFailure extends RuntimeException
        permits ProductServiceUnavailableException, ProductServiceErrorException {

    protected ProductServiceFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
