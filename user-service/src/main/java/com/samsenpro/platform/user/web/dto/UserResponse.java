package com.samsenpro.platform.user.web.dto;

import com.samsenpro.platform.user.domain.Role;
import com.samsenpro.platform.user.domain.User;

import java.time.Instant;
import java.util.UUID;

/** Vista pública de un usuario: nunca incluye el hash de la contraseña. */
public record UserResponse(
        UUID id,
        String username,
        String email,
        Role role,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole(),
                user.isEnabled(), user.getCreatedAt(), user.getUpdatedAt());
    }
}
