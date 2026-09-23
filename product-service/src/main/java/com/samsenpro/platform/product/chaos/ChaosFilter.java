package com.samsenpro.platform.product.chaos;

import com.samsenpro.platform.commons.error.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/** Aplica el fallo configurado en {@link ChaosController} antes de que la petición llegue al controlador. */
class ChaosFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ChaosFilter.class);

    private final AtomicReference<ChaosSettings> settings;
    private final ApiErrorWriter errorWriter;

    ChaosFilter(AtomicReference<ChaosSettings> settings, ApiErrorWriter errorWriter) {
        this.settings = settings;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ChaosSettings current = settings.get();
        if (current.mode() == ChaosSettings.Mode.NONE || ThreadLocalRandom.current().nextDouble() >= current.failureRate()) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Chaos: injecting {} into {} {}", current.mode(), request.getMethod(), request.getRequestURI());
        switch (current.mode()) {
            case DELAY -> {
                sleep(current.delayMs());
                chain.doFilter(request, response);
            }
            case ERROR_500 -> errorWriter.write(request, response, HttpStatus.INTERNAL_SERVER_ERROR,
                    "CHAOS_SIMULATED_FAILURE", "Simulated internal error (chaos mode)");
            case ERROR_503 -> errorWriter.write(request, response, HttpStatus.SERVICE_UNAVAILABLE,
                    "CHAOS_SIMULATED_FAILURE", "Simulated unavailability (chaos mode)");
            default -> chain.doFilter(request, response);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
