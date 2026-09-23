package com.samsenpro.platform.commons.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Configuración JWT compartida por toda la plataforma (config-repo/application.yml). El secreto llega
 * siempre por la variable de entorno JWT_SECRET; nunca está en el repositorio.
 *
 * @param issuer     valor del claim {@code iss}; los tokens con otro emisor se rechazan
 * @param secret     clave HMAC-SHA256 (mínimo 32 bytes)
 * @param expiration validez del token emitido (solo la usa user-service)
 */
@Validated
@ConfigurationProperties("security.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank String secret,
        @DefaultValue("1h") Duration expiration) {

    public static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret != null && !secret.isBlank() && secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "security.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes for HS256");
        }
    }

    public byte[] secretBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }
}
