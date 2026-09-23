package com.samsenpro.platform.commons.security;

import com.samsenpro.platform.commons.error.ApiErrorWriter;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Base común de la cadena de seguridad de cada servicio: stateless, JWT validado localmente y errores
 * 401/403 en el formato de la plataforma. Las reglas de autorización de cada recurso las define el
 * propio servicio: no se confía en que el gateway ya haya filtrado la petición.
 */
public class PlatformHttpSecurity {

    private static final String[] PUBLIC_PATHS = {
            "/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error"
    };

    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final AccessDeniedHandler accessDeniedHandler;

    public PlatformHttpSecurity(JwtAuthenticationConverter jwtAuthenticationConverter, ApiErrorWriter errorWriter) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.authenticationEntryPoint = (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            errorWriter.write(request, response, HttpStatus.UNAUTHORIZED, unauthorizedCode(ex),
                    "Authentication is required to access this resource");
        };
        this.accessDeniedHandler = (request, response, ex) -> errorWriter.write(request, response,
                HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action");
    }

    public SecurityFilterChain build(HttpSecurity http,
                                     Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>
                                             .AuthorizationManagerRequestMatcherRegistry> serviceRules)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(PUBLIC_PATHS).permitAll();
                    auth.requestMatchers("/actuator/**").hasRole("ADMIN");
                    serviceRules.customize(auth);
                    auth.anyRequest().authenticated();
                })
                .build();
    }

    private static String unauthorizedCode(AuthenticationException ex) {
        return ex instanceof OAuth2AuthenticationException ? "INVALID_TOKEN" : "UNAUTHORIZED";
    }
}
