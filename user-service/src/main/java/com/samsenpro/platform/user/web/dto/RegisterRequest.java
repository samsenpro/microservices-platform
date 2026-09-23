package com.samsenpro.platform.user.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Schema(example = "alice")
        @NotBlank @Size(min = 3, max = 50)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "only letters, digits, '.', '_' and '-' are allowed")
        String username,

        @Schema(example = "alice@example.com")
        @NotBlank @Email @Size(max = 254)
        String email,

        // 72 bytes es el límite de BCrypt: por encima, el resto de la contraseña se ignoraría
        @Schema(example = "S3cure-Passw0rd")
        @NotBlank @Size(min = 8, max = 72)
        String password) {

    /** Evita que la contraseña acabe en un log si alguien registra el objeto por error. */
    @Override
    public String toString() {
        return "RegisterRequest[username=" + username + ", email=" + email + ", password=****]";
    }
}
