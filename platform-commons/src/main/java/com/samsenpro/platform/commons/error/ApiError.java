package com.samsenpro.platform.commons.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** Formato único de error de toda la plataforma (servicios y gateway). Nunca incluye stack traces. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> errors) {

    public record FieldViolation(String field, String message) {
    }
}
