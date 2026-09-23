package com.samsenpro.platform.gateway.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;

/** Mismas propiedades security.jwt que usan los servicios (config-repo/application.yml). */
@Validated
@ConfigurationProperties("security.jwt")
public record GatewayJwtProperties(@NotBlank String issuer, @NotBlank String secret) {

    public GatewayJwtProperties {
        if (secret != null && !secret.isBlank() && secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("security.jwt.secret must be at least 32 bytes for HS256");
        }
    }

    public byte[] secretBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }
}
