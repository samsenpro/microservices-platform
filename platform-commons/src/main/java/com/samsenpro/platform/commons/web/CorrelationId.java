package com.samsenpro.platform.commons.web;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/** Cabecera y clave MDC del correlation ID que el gateway asigna a cada petición. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    /** Evita que un cliente inyecte valores arbitrarios (saltos de línea, cadenas enormes) en los logs. */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private CorrelationId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static String sanitizeOrGenerate(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }
}
