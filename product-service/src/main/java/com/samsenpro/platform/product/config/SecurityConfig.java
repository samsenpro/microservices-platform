package com.samsenpro.platform.product.config;

import com.samsenpro.platform.commons.security.PlatformHttpSecurity;
import com.samsenpro.platform.product.chaos.ChaosController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Autorización propia del servicio: aunque el gateway ya validó el JWT, aquí se vuelve a validar y se
 * aplican los roles de cada operación.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PlatformHttpSecurity platform) throws Exception {
        return platform.build(http, auth -> auth
                // Solo existe con el perfil "chaos" y solo acepta peticiones desde el propio contenedor
                .requestMatchers(ChaosController.PATH).access((authentication, context) ->
                        new AuthorizationDecision(ChaosController.isLoopback(context.getRequest())))
                .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/*").hasAnyRole("USER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/products").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/products/*").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/products/*").hasRole("ADMIN"));
    }
}
