package com.samsenpro.platform.commons.testing;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Genera JWT de prueba firmados con el mismo algoritmo que user-service. Se publica como test-jar para
 * que los tests de los servicios compartan la misma forma de construir tokens.
 */
public final class TestJwts {

    public static final String SECRET = "test-secret-for-unit-and-integration-tests-only-0123456789";
    public static final String ISSUER = "microservices-platform";

    private TestJwts() {
    }

    public static String user(UUID userId) {
        return token(SECRET, ISSUER, userId.toString(), "USER", Duration.ofMinutes(15));
    }

    public static String admin(UUID userId) {
        return token(SECRET, ISSUER, userId.toString(), "ADMIN", Duration.ofMinutes(15));
    }

    public static String token(String secret, String issuer, String subject, String role, Duration validity) {
        Instant now = Instant.now();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(validity.isNegative() ? now.plus(validity).minusSeconds(60) : now)
                .expiresAt(now.plus(validity));
        if (role != null) {
            claims.claim("role", role);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
