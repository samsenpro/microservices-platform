package com.samsenpro.platform.user.service;

import com.samsenpro.platform.commons.error.ApiException;
import com.samsenpro.platform.commons.security.AuthenticatedUser;
import com.samsenpro.platform.commons.web.PageResponse;
import com.samsenpro.platform.user.domain.Role;
import com.samsenpro.platform.user.domain.User;
import com.samsenpro.platform.user.domain.UserRepository;
import com.samsenpro.platform.user.web.dto.RegisterRequest;
import com.samsenpro.platform.user.web.dto.UpdateUserRequest;
import com.samsenpro.platform.user.web.dto.UserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** El registro público siempre crea usuarios con rol USER; los ADMIN los asigna otro ADMIN. */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (!PasswordRules.fitsBcrypt(request.password())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG",
                    "Password must not exceed " + PasswordRules.BCRYPT_MAX_BYTES + " bytes");
        }
        if (users.existsByUsernameIgnoreCase(request.username())) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "Username is already registered");
        }
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Email is already registered");
        }
        // saveAndFlush: el INSERT se ejecuta ya y createdAt/updatedAt quedan informados en la respuesta
        User user = users.saveAndFlush(new User(request.username(), request.email(),
                passwordEncoder.encode(request.password()), Role.USER));
        log.info("User {} registered", user.getId());
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id, AuthenticatedUser caller) {
        // Un USER solo puede verse a sí mismo; para cualquier otro id responde como si no existiera.
        if (!caller.isAdmin() && !caller.id().equals(id)) {
            throw notFound();
        }
        return users.findById(id).map(UserResponse::from).orElseThrow(UserService::notFound);
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(Pageable pageable) {
        Page<User> page = users.findAll(pageable);
        return new PageResponse<>(page.map(UserResponse::from).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request, AuthenticatedUser caller) {
        if (caller.id().equals(id)) {
            // Evita que el último administrador se quite el rol o se bloquee a sí mismo por error
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_MODIFICATION_NOT_ALLOWED",
                    "Administrators cannot change their own role or status");
        }
        User user = users.findById(id).orElseThrow(UserService::notFound);
        if (request.role() != null) {
            user.changeRole(request.role());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        log.info("User {} updated by admin {}: role={}, enabled={}", id, caller.id(), user.getRole(),
                user.isEnabled());
        return UserResponse.from(users.saveAndFlush(user));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
    }
}
