package com.samsenpro.platform.user.service;

import com.samsenpro.platform.user.domain.Role;
import com.samsenpro.platform.user.domain.User;
import com.samsenpro.platform.user.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Crea el primer administrador a partir de variables de entorno (ADMIN_USERNAME, ADMIN_EMAIL,
 * ADMIN_PASSWORD). Si ADMIN_PASSWORD no está definida no se crea nada: no hay credenciales por defecto.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Properties properties;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, Properties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.password() == null || properties.password().isBlank()) {
            log.info("ADMIN_PASSWORD not set: skipping bootstrap administrator");
            return;
        }
        if (properties.password().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("ADMIN_PASSWORD must have at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (users.existsByUsernameIgnoreCase(properties.username())) {
            return;
        }
        try {
            users.saveAndFlush(new User(properties.username(), properties.email(),
                    passwordEncoder.encode(properties.password()), Role.ADMIN));
            log.info("Bootstrap administrator '{}' created", properties.username());
        } catch (DataIntegrityViolationException alreadyCreatedByAnotherInstance) {
            log.info("Bootstrap administrator already created by another instance");
        }
    }

    @ConfigurationProperties("users.bootstrap-admin")
    public record Properties(String username, String email, String password) {

        @Override
        public String toString() {
            return "Properties[username=" + username + ", email=" + email + ", password=****]";
        }
    }
}
