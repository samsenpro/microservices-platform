package com.samsenpro.platform.user.web.dto;

import com.samsenpro.platform.user.domain.Role;

/** Cambios administrativos sobre un usuario; los campos nulos no se modifican. */
public record UpdateUserRequest(Role role, Boolean enabled) {
}
