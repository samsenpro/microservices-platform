package com.samsenpro.platform.user.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @Schema(example = "alice")
        @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Za-z0-9._-]+$")
        String username,

        @Schema(example = "S3cure-Passw0rd")
        @NotBlank @Size(max = 72)
        String password) {

    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=****]";
    }
}
