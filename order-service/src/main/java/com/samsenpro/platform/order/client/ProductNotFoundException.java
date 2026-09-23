package com.samsenpro.platform.order.client;

import com.samsenpro.platform.commons.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * product-service respondió 404: es un error del pedido, no de la dependencia. No se reintenta y el
 * Circuit Breaker lo cuenta como llamada correcta.
 */
public class ProductNotFoundException extends ApiException {

    public ProductNotFoundException(Long productId) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCT_NOT_FOUND", "Product " + productId + " does not exist");
    }
}
