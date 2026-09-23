package com.samsenpro.platform.order.client;

import com.samsenpro.platform.commons.error.ApiException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Acceso resiliente a product-service. Composición (de fuera hacia dentro):
 *
 * <pre>
 * Retry ─► CircuitBreaker ─► Bulkhead ─► HTTP con timeout
 * </pre>
 *
 * <ul>
 *   <li><b>Timeout</b> (cliente HTTP): ninguna llamada espera más de {@code read-timeout}.</li>
 *   <li><b>Bulkhead</b>: limita las llamadas simultáneas; el permiso se libera entre reintentos.</li>
 *   <li><b>Circuit Breaker</b>: cada intento cuenta; con demasiados fallos o llamadas lentas se abre y
 *       las siguientes peticiones fallan al instante sin tocar la red.</li>
 *   <li><b>Retry</b>: solo errores transitorios, con backoff exponencial y un máximo de intentos.
 *       Con el circuito abierto no se reintenta.</li>
 * </ul>
 *
 * El "fallback" no inventa datos (no hay un precio o un stock razonables por defecto): convierte cada fallo
 * en un error controlado y específico, sin esperar indefinidamente.
 */
@Service
public class ResilientProductClient {

    public static final String INSTANCE = "productService";
    private static final Logger log = LoggerFactory.getLogger(ResilientProductClient.class);

    private final ProductClient productClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;

    ResilientProductClient(ProductClient productClient, CircuitBreakerRegistry circuitBreakers,
                           RetryRegistry retries, BulkheadRegistry bulkheads) {
        this.productClient = productClient;
        this.circuitBreaker = circuitBreakers.circuitBreaker(INSTANCE);
        this.retry = retries.retry(INSTANCE);
        this.bulkhead = bulkheads.bulkhead(INSTANCE);

        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("Circuit breaker '{}' changed state: {}", INSTANCE, event.getStateTransition()));
        retry.getEventPublisher().onRetry(event ->
                log.warn("Retrying product-service call (retry #{}) after: {}", event.getNumberOfRetryAttempts(),
                        event.getLastThrowable().getMessage()));
    }

    public ProductSnapshot getProduct(Long productId) {
        Supplier<ProductSnapshot> call = () -> productClient.getProduct(productId);
        Supplier<ProductSnapshot> resilientCall = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker,
                        Bulkhead.decorateSupplier(bulkhead, call)));
        try {
            return resilientCall.get();
        } catch (CallNotPermittedException e) {
            log.warn("Circuit breaker '{}' is OPEN: failing fast for product {}", INSTANCE, productId);
            throw unavailable();
        } catch (BulkheadFullException e) {
            log.warn("Bulkhead '{}' is full: rejecting call for product {}", INSTANCE, productId);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRODUCT_SERVICE_BUSY",
                    "Product service is saturated, retry later");
        } catch (ProductServiceUnavailableException e) {
            log.error("Product service unavailable after retries ({}): {}", e.getReason(), e.getMessage());
            if (e.getReason() == ProductServiceUnavailableException.Reason.TIMEOUT) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "PRODUCT_SERVICE_TIMEOUT",
                        "Product service did not respond in time");
            }
            throw unavailable();
        } catch (ProductServiceErrorException e) {
            log.error("Product service failed with HTTP {} for product {}", e.getStatus(), productId);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PRODUCT_SERVICE_ERROR",
                    "Product service failed to process the request");
        }
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRODUCT_SERVICE_UNAVAILABLE",
                "Product service is temporarily unavailable");
    }
}
