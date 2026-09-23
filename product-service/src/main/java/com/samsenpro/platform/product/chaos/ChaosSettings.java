package com.samsenpro.platform.product.chaos;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Fallo que se inyecta en las peticiones a /api/products.
 *
 * @param mode        NONE, DELAY (responde, pero tarde), ERROR_500 o ERROR_503
 * @param delayMs     retardo para el modo DELAY
 * @param failureRate proporción de peticiones afectadas (1.0 = todas)
 */
public record ChaosSettings(
        @NotNull Mode mode,
        @Min(0) @Max(30_000) long delayMs,
        @DecimalMin("0.0") @DecimalMax("1.0") double failureRate) {

    public static final ChaosSettings OFF = new ChaosSettings(Mode.NONE, 0, 0.0);

    public enum Mode {
        NONE,
        DELAY,
        ERROR_500,
        ERROR_503
    }
}
