package com.samsenpro.platform.gateway.error;

import java.time.Instant;

/**
 * Mismo formato de error que los servicios (platform-commons). El gateway es reactivo y no puede depender
 * de platform-commons (servlet), así que replica solo este contrato JSON.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId) {
}
