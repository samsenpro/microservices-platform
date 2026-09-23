package com.samsenpro.platform.gateway.discovery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.netflix.eureka.http.EurekaClientHttpRequestFactorySupplier;
import org.springframework.cloud.netflix.eureka.http.RestTemplateDiscoveryClientOptionalArgs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Credenciales de Eureka como cabecera basic auth, no dentro de la URL de {@code defaultZone}: el cliente de
 * Netflix escribe esa URL en el log cuando falla un heartbeat, y con ella la contraseña.
 * (Misma solución que {@code EurekaClientAuthentication} de platform-commons, que el gateway reactivo no usa).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "eureka.client", name = "enabled", matchIfMissing = true)
class EurekaClientAuthenticationConfig {

    @Bean
    RestTemplateDiscoveryClientOptionalArgs eurekaDiscoveryClientOptionalArgs(
            EurekaClientHttpRequestFactorySupplier requestFactory,
            @Value("${platform.eureka.username:}") String username,
            @Value("${platform.eureka.password:}") String password) {
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("EUREKA_USERNAME and EUREKA_PASSWORD must be set");
        }
        return new RestTemplateDiscoveryClientOptionalArgs(requestFactory,
                () -> new RestTemplateBuilder().basicAuthentication(username, password));
    }
}
