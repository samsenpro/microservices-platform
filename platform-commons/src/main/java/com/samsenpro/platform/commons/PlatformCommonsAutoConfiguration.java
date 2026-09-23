package com.samsenpro.platform.commons;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsenpro.platform.commons.error.ApiErrorWriter;
import com.samsenpro.platform.commons.error.GlobalExceptionHandler;
import com.samsenpro.platform.commons.security.AuthenticatedUser;
import com.samsenpro.platform.commons.security.JwtProperties;
import com.samsenpro.platform.commons.security.PlatformHttpSecurity;
import com.samsenpro.platform.commons.web.CorrelationIdFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import javax.crypto.spec.SecretKeySpec;
import java.util.List;
import java.util.Set;

/**
 * Registra las piezas comunes en cada servicio de negocio que añade platform-commons como dependencia.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(JwtProperties.class)
public class PlatformCommonsAutoConfiguration {

    private static final Set<String> ROLES = Set.of("USER", "ADMIN");

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    ApiErrorWriter apiErrorWriter(ObjectMapper objectMapper) {
        return new ApiErrorWriter(objectMapper);
    }

    @Bean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /**
     * Valida firma HS256, expiración/notBefore (con 60 s de tolerancia), emisor y que el rol sea conocido.
     */
    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(properties.secretBytes(), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> roleValidator =
                new JwtClaimValidator<Object>(AuthenticatedUser.ROLE_CLAIM, role -> role != null && ROLES.contains(role));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), roleValidator));
        return decoder;
    }

    /** El claim {@code role} (USER / ADMIN) se convierte en la autoridad ROLE_USER / ROLE_ADMIN. */
    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(AuthenticatedUser.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    PlatformHttpSecurity platformHttpSecurity(JwtAuthenticationConverter converter, ApiErrorWriter errorWriter) {
        return new PlatformHttpSecurity(converter, errorWriter);
    }

    /**
     * El servidor es "/" porque el Swagger UI se sirve desde el gateway: "Try it out" pasa por él, igual que
     * cualquier cliente real.
     */
    @Bean
    @ConditionalOnMissingBean
    OpenAPI platformOpenApi(@Value("${platform.openapi.title:${spring.application.name}}") String title,
                            @Value("${platform.openapi.description:}") String description,
                            @Value("${platform.openapi.version:1.0.0}") String version) {
        return new OpenAPI()
                .info(new Info().title(title).description(description).version(version))
                .servers(List.of(new Server().url("/").description("API Gateway")))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
