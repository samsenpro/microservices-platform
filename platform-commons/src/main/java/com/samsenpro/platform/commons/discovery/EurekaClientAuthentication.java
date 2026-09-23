package com.samsenpro.platform.commons.discovery;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.netflix.eureka.http.EurekaClientHttpRequestFactorySupplier;
import org.springframework.cloud.netflix.eureka.http.RestTemplateDiscoveryClientOptionalArgs;

/**
 * Autenticación del cliente Eureka con basic auth enviada como cabecera, no dentro de la URL.
 *
 * <p>Con credenciales en {@code defaultZone} (http://usuario:clave@host/eureka/), el cliente de Netflix
 * escribe la URL completa, contraseña incluida, en el log cada vez que falla un heartbeat. Aquí la URL
 * va sin credenciales y el usuario y la contraseña se añaden a cada petición.
 */
public final class EurekaClientAuthentication {

    private EurekaClientAuthentication() {
    }

    public static RestTemplateDiscoveryClientOptionalArgs optionalArgs(EurekaClientHttpRequestFactorySupplier requestFactory,
                                                                       String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("EUREKA_USERNAME and EUREKA_PASSWORD must be set");
        }
        return new RestTemplateDiscoveryClientOptionalArgs(requestFactory,
                () -> new RestTemplateBuilder().basicAuthentication(username, password));
    }
}
