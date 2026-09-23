package com.samsenpro.platform.user.service;

import java.nio.charset.StandardCharsets;

/**
 * BCrypt solo tiene en cuenta los primeros 72 BYTES y Spring Security rechaza (con una excepción) cualquier
 * contraseña más larga. {@code @Size(max = 72)} cuenta caracteres: 72 caracteres con tildes o eñes pueden
 * ocupar más de 72 bytes, así que se comprueba también en bytes.
 */
final class PasswordRules {

    static final int BCRYPT_MAX_BYTES = 72;

    private PasswordRules() {
    }

    static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }
}
