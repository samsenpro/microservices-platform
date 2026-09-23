package com.samsenpro.platform.order.config;

import com.samsenpro.platform.commons.security.PlatformHttpSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** La propiedad de cada pedido (dueño o ADMIN) se comprueba en OrderService. */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PlatformHttpSecurity platform) throws Exception {
        return platform.build(http, auth -> auth
                .requestMatchers(HttpMethod.PATCH, "/api/orders/*/status").hasRole("ADMIN")
                .requestMatchers("/api/orders", "/api/orders/**").hasAnyRole("USER", "ADMIN"));
    }
}
