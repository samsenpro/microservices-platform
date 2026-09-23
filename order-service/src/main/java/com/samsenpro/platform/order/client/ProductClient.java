package com.samsenpro.platform.order.client;

import com.samsenpro.platform.commons.error.ApiException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static com.samsenpro.platform.order.client.ProductServiceUnavailableException.Reason;

/**
 * Llamada HTTP "desnuda" a product-service. Su única responsabilidad es traducir el resultado a una
 * excepción que distinga fallos transitorios, fallos permanentes y errores de negocio; las políticas de
 * resiliencia las aplica {@link ResilientProductClient}.
 */
@Component
class ProductClient {

    private static final Logger log = LoggerFactory.getLogger(ProductClient.class);

    private final RestClient restClient;

    ProductClient(RestClient productRestClient) {
        this.restClient = productRestClient;
    }

    ProductSnapshot getProduct(Long productId) {
        try {
            return restClient.get()
                    .uri("/api/products/{id}", productId)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return response.bodyTo(ProductSnapshot.class);
                        }
                        if (status == HttpStatus.NOT_FOUND) {
                            throw new ProductNotFoundException(productId);
                        }
                        if (status == HttpStatus.BAD_GATEWAY || status == HttpStatus.SERVICE_UNAVAILABLE
                                || status == HttpStatus.GATEWAY_TIMEOUT) {
                            throw new ProductServiceUnavailableException(Reason.UNAVAILABLE_RESPONSE,
                                    "Product service answered HTTP " + response.getStatusCode().value(), null);
                        }
                        if (response.getStatusCode().is5xxServerError()) {
                            throw new ProductServiceErrorException(response.getStatusCode().value());
                        }
                        // 400/401/403/409...: el servicio está sano pero rechazó la petición. No es un fallo
                        // transitorio (no se reintenta) y apunta a un problema de integración, así que se registra.
                        log.error("Product service rejected the request for product {} with HTTP {}", productId,
                                response.getStatusCode().value());
                        throw new ApiException(HttpStatus.BAD_GATEWAY, "PRODUCT_SERVICE_REJECTED_REQUEST",
                                "Product service rejected the request");
                    });
        } catch (ResourceAccessException e) {
            throw classifyIoFailure(e);
        } catch (IllegalStateException e) {
            // Lanzada por Spring Cloud LoadBalancer cuando Eureka no tiene ninguna instancia UP
            if (e.getMessage() != null && e.getMessage().startsWith("No instances available")) {
                throw new ProductServiceUnavailableException(Reason.NO_INSTANCES,
                        "No product-service instances registered", e);
            }
            throw e;
        }
    }

    private static ProductServiceUnavailableException classifyIoFailure(ResourceAccessException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ConnectTimeoutException) {
            return new ProductServiceUnavailableException(Reason.CONNECTION_FAILED, "Connection timeout", e);
        }
        if (cause instanceof SocketTimeoutException) {
            return new ProductServiceUnavailableException(Reason.TIMEOUT, "Product service did not answer in time", e);
        }
        return new ProductServiceUnavailableException(Reason.CONNECTION_FAILED,
                "Cannot connect to product service: " + e.getMessage(), e);
    }
}
