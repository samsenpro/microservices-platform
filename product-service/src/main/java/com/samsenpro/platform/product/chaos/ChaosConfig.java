package com.samsenpro.platform.product.chaos;

import com.samsenpro.platform.commons.error.ApiErrorWriter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

import java.util.concurrent.atomic.AtomicReference;

/** Nada de este paquete se carga sin el perfil "chaos". */
@Configuration
@Profile("chaos")
class ChaosConfig {

    private static final Logger log = LoggerFactory.getLogger(ChaosConfig.class);

    @PostConstruct
    void warn() {
        log.warn("CHAOS MODE ENABLED: failure injection is available at {}. Never enable it in production.",
                ChaosController.PATH);
    }

    @Bean
    AtomicReference<ChaosSettings> chaosSettings() {
        return new AtomicReference<>(ChaosSettings.OFF);
    }

    /** Justo después del filtro de correlation ID: el fallo inyectado también lleva correlationId. */
    @Bean
    FilterRegistrationBean<ChaosFilter> chaosFilter(AtomicReference<ChaosSettings> chaosSettings,
                                                   ApiErrorWriter errorWriter) {
        FilterRegistrationBean<ChaosFilter> registration =
                new FilterRegistrationBean<>(new ChaosFilter(chaosSettings, errorWriter));
        registration.addUrlPatterns("/api/products", "/api/products/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
