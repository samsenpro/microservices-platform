package com.samsenpro.platform.user.service;

import com.samsenpro.platform.commons.error.ApiException;
import com.samsenpro.platform.commons.security.JwtProperties;
import com.samsenpro.platform.commons.testing.TestJwts;
import com.samsenpro.platform.user.domain.Role;
import com.samsenpro.platform.user.domain.User;
import com.samsenpro.platform.user.domain.UserRepository;
import com.samsenpro.platform.user.web.dto.LoginRequest;
import com.samsenpro.platform.user.web.dto.TokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenReturn("$2a$10$dummy");
        JwtTokenService tokens = new JwtTokenService(
                new JwtProperties(TestJwts.ISSUER, TestJwts.SECRET, Duration.ofMinutes(30)), Clock.systemUTC());
        authService = new AuthService(users, encoder, tokens);
    }

    @Test
    void issuesBearerTokenForValidCredentials() {
        User alice = new User("alice", "alice@example.com", "$2a$10$hash", Role.USER);
        when(users.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(alice));
        when(encoder.matches("S3cure-Passw0rd", "$2a$10$hash")).thenReturn(true);

        TokenResponse response = authService.login(new LoginRequest("alice", "S3cure-Passw0rd"));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(1800);
        assertThat(response.accessToken()).isNotBlank();
    }

    @Test
    void unknownUserStillPaysTheBcryptCost() {
        when(users.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "whatever-password")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_CREDENTIALS"));
        verify(encoder, times(1)).matches(eq("whatever-password"), eq("$2a$10$dummy"));
    }

    @Test
    void disabledAccountIsIndistinguishableFromWrongPassword() {
        User bob = new User("bob", "bob@example.com", "$2a$10$hash", Role.USER);
        bob.setEnabled(false);
        when(users.findByUsernameIgnoreCase("bob")).thenReturn(Optional.of(bob));
        when(encoder.matches("S3cure-Passw0rd", "$2a$10$hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("bob", "S3cure-Passw0rd")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getMessage()).isEqualTo("Invalid username or password");
                });
    }

    @Test
    void requestObjectsNeverPrintThePassword() {
        assertThat(new LoginRequest("alice", "S3cure-Passw0rd").toString()).doesNotContain("S3cure-Passw0rd");
        assertThat(TokenResponse.bearer("eyJhbGciOi.secret.token", 60).toString()).doesNotContain("eyJ");
    }
}
