package com.samsenpro.platform.order.client;

import com.samsenpro.platform.commons.error.ApiException;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static com.samsenpro.platform.order.client.ProductServiceUnavailableException.Reason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test unitario de la composición Retry → CircuitBreaker → Bulkhead y del fallback, sin Spring ni red:
 * el cliente HTTP es un mock y los registros de Resilience4j se construyen con la misma semántica que
 * config-repo/order-service.yml.
 */
class ResilientProductClientTest {

    private static final ProductSnapshot PRODUCT = new ProductSnapshot(1L, "Keyboard", new BigDecimal("49.90"), 10, true);

    private final ProductClient http = mock(ProductClient.class);
    private CircuitBreakerRegistry circuitBreakers;
    private BulkheadRegistry bulkheads;
    private ResilientProductClient client;

    @BeforeEach
    void setUp() {
        circuitBreakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(ProductServiceFailure.class)
                .ignoreExceptions(io.github.resilience4j.bulkhead.BulkheadFullException.class)
                .build());
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(ProductServiceUnavailableException.class)
                .build());
        bulkheads = BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        client = new ResilientProductClient(http, circuitBreakers, retries, bulkheads);
    }

    @Test
    void transientFailureIsRetriedUntilSuccess() {
        when(http.getProduct(1L))
                .thenThrow(unavailable(Reason.UNAVAILABLE_RESPONSE))
                .thenThrow(unavailable(Reason.CONNECTION_FAILED))
                .thenReturn(PRODUCT);

        assertThat(client.getProduct(1L)).isEqualTo(PRODUCT);
        verify(http, times(3)).getProduct(1L);
    }

    @Test
    void persistentUnavailabilityFallsBackTo503AfterThreeAttempts() {
        when(http.getProduct(1L)).thenThrow(unavailable(Reason.UNAVAILABLE_RESPONSE));

        assertThatThrownBy(() -> client.getProduct(1L)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(503);
            assertThat(ex.getCode()).isEqualTo("PRODUCT_SERVICE_UNAVAILABLE");
        });
        verify(http, times(3)).getProduct(1L);
    }

    @Test
    void timeoutsFallBackTo504() {
        when(http.getProduct(1L)).thenThrow(unavailable(Reason.TIMEOUT));

        assertThatThrownBy(() -> client.getProduct(1L)).isInstanceOfSatisfying(ApiException.class, ex ->
                assertThat(ex.getCode()).isEqualTo("PRODUCT_SERVICE_TIMEOUT"));
    }

    @Test
    void serverErrorsAreNotRetriedButReportedAs502() {
        when(http.getProduct(1L)).thenThrow(new ProductServiceErrorException(500));

        assertThatThrownBy(() -> client.getProduct(1L)).isInstanceOfSatisfying(ApiException.class, ex ->
                assertThat(ex.getCode()).isEqualTo("PRODUCT_SERVICE_ERROR"));
        verify(http, times(1)).getProduct(1L);
    }

    @Test
    void businessErrorsPassThroughWithoutRetryAndDoNotCountAsFailures() {
        when(http.getProduct(1L)).thenThrow(new ProductNotFoundException(1L));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.getProduct(1L)).isInstanceOf(ProductNotFoundException.class);
        }

        verify(http, times(5)).getProduct(1L);
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void openCircuitFailsFastWithoutCallingTheDependency() {
        circuitBreakers.circuitBreaker(ResilientProductClient.INSTANCE).transitionToOpenState();

        assertThatThrownBy(() -> client.getProduct(1L)).isInstanceOfSatisfying(ApiException.class, ex ->
                assertThat(ex.getCode()).isEqualTo("PRODUCT_SERVICE_UNAVAILABLE"));
        verifyNoInteractions(http);
    }

    @Test
    void repeatedDependencyFailuresOpenTheCircuit() {
        when(http.getProduct(1L)).thenThrow(new ProductServiceErrorException(500));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.getProduct(1L)).isInstanceOf(ApiException.class);
        }

        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void fullBulkheadRejectsImmediatelyWithBusyError() {
        // Ocupa el único permiso, como haría otra petición en curso contra un product-service lento
        assertThat(bulkheads.bulkhead(ResilientProductClient.INSTANCE).tryAcquirePermission()).isTrue();

        assertThatThrownBy(() -> client.getProduct(1L)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(503);
            assertThat(ex.getCode()).isEqualTo("PRODUCT_SERVICE_BUSY");
        });
        verifyNoInteractions(http);
        // Rechazar por bulkhead no es un fallo de la dependencia
        assertThat(circuitBreakers.circuitBreaker(ResilientProductClient.INSTANCE).getMetrics().getNumberOfFailedCalls())
                .isZero();
    }

    private static ProductServiceUnavailableException unavailable(Reason reason) {
        return new ProductServiceUnavailableException(reason, "simulated " + reason, null);
    }
}
