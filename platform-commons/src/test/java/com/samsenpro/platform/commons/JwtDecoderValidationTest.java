package com.samsenpro.platform.commons;

import com.samsenpro.platform.commons.security.JwtProperties;
import com.samsenpro.platform.commons.testing.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.BadJwtException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtDecoderValidationTest {

    private final JwtDecoder decoder = new PlatformCommonsAutoConfiguration()
            .jwtDecoder(new JwtProperties(TestJwts.ISSUER, TestJwts.SECRET, Duration.ofHours(1)));

    @Test
    void acceptsValidTokenAndExposesSubjectAndRole() {
        UUID userId = UUID.randomUUID();

        Jwt jwt = decoder.decode(TestJwts.admin(userId));

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        String forged = TestJwts.token("another-secret-with-enough-length-for-hs256-000000", TestJwts.ISSUER,
                UUID.randomUUID().toString(), "ADMIN", Duration.ofMinutes(5));

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsExpiredToken() {
        String expired = TestJwts.token(TestJwts.SECRET, TestJwts.ISSUER, UUID.randomUUID().toString(), "USER",
                Duration.ofMinutes(-5));

        assertThatThrownBy(() -> decoder.decode(expired))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rejectsTokenFromAnotherIssuer() {
        String foreign = TestJwts.token(TestJwts.SECRET, "someone-else", UUID.randomUUID().toString(), "USER",
                Duration.ofMinutes(5));

        assertThatThrownBy(() -> decoder.decode(foreign)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsUnknownOrMissingRole() {
        String unknownRole = TestJwts.token(TestJwts.SECRET, TestJwts.ISSUER, UUID.randomUUID().toString(),
                "ROOT", Duration.ofMinutes(5));
        String noRole = TestJwts.token(TestJwts.SECRET, TestJwts.ISSUER, UUID.randomUUID().toString(),
                null, Duration.ofMinutes(5));

        assertThatThrownBy(() -> decoder.decode(unknownRole)).isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decoder.decode(noRole)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void refusesSecretsShorterThan256Bits() {
        assertThatThrownBy(() -> new JwtProperties(TestJwts.ISSUER, "too-short", Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
