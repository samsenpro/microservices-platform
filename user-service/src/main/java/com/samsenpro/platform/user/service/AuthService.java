package com.samsenpro.platform.user.service;

import com.samsenpro.platform.commons.error.ApiException;
import com.samsenpro.platform.user.domain.User;
import com.samsenpro.platform.user.domain.UserRepository;
import com.samsenpro.platform.user.web.dto.LoginRequest;
import com.samsenpro.platform.user.web.dto.TokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    /** Hash de referencia para que un usuario inexistente cueste lo mismo que una contraseña incorrecta. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Usuario inexistente, contraseña incorrecta y cuenta deshabilitada devuelven exactamente el mismo
     * error, y el mismo coste de BCrypt, para no revelar qué usuarios existen.
     */
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        if (!PasswordRules.fitsBcrypt(request.password())) {
            // Ninguna contraseña almacenada puede ser así de larga
            throw invalidCredentials(request.username());
        }
        User user = users.findByUsernameIgnoreCase(request.username()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw invalidCredentials(request.username());
        }
        if (!passwordEncoder.matches(request.password(), user.getPassword()) || !user.isEnabled()) {
            throw invalidCredentials(request.username());
        }
        log.info("User {} authenticated", user.getId());
        return TokenResponse.bearer(tokens.issue(user), tokens.expiresInSeconds());
    }

    private static ApiException invalidCredentials(String username) {
        log.warn("Failed login attempt for username '{}'", username);
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password");
    }
}
