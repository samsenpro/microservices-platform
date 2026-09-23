package com.samsenpro.platform.commons.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Coloca el correlation ID recibido del gateway en el MDC (aparece en todos los logs de la petición) y en
 * la respuesta. Si la petición no pasó por el gateway (por ejemplo, un health check), se genera uno.
 * Se registra antes que la cadena de Spring Security para que también los 401/403 lo incluyan.
 *
 * <p>Escribe además una línea de acceso por petición (método, ruta, estado, duración; nunca cabeceras ni
 * cuerpos). Los endpoints de Actuator se omiten para no llenar los logs con los health checks.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = CorrelationId.sanitizeOrGenerate(request.getHeader(CorrelationId.HEADER));
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (!request.getRequestURI().startsWith("/actuator")) {
                log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(),
                        (System.nanoTime() - start) / 1_000_000);
            }
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
