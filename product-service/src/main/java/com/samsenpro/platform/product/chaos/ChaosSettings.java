package com.samsenpro.platform.product.chaos;

/**
 * Fallo que se inyecta en las peticiones a /api/products.
 *
 * @param mode        NONE, DELAY (responde, pero tarde), ERROR_500 o ERROR_503
 * @param delayMs     retardo para el modo DELAY
 * @param failureRate proporción de peticiones afectadas (1.0 = todas)
 */
public record ChaosSettings(Mode mode, long delayMs, double failureRate) {

    public static final ChaosSettings OFF = new ChaosSettings(Mode.NONE, 0, 0.0);

    public enum Mode {
        NONE,
        DELAY,
        ERROR_500,
        ERROR_503
    }
}
