package com.samsenpro.platform.commons.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Identidad del usuario de la petición, extraída del JWT ya validado por el propio servicio
 * (no de cabeceras añadidas por el gateway).
 */
public record AuthenticatedUser(UUID id, String role) {

    public static final String ROLE_CLAIM = "role";

    public static AuthenticatedUser current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No authenticated JWT principal in the security context");
        }
        return new AuthenticatedUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString(ROLE_CLAIM));
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
