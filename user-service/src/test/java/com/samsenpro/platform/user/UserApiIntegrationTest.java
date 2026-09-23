package com.samsenpro.platform.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsenpro.platform.commons.testing.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void registerLoginAndReadOwnProfile() throws Exception {
        String username = uniqueName();
        register(username, "S3cure-Passw0rd")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist());

        String token = login(username, "S3cure-Passw0rd");

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.email").value(username + "@example.com"));
    }

    @Test
    void issuedTokenCarriesOnlyTheMinimalClaims() throws Exception {
        String username = uniqueName();
        register(username, "S3cure-Passw0rd").andExpect(status().isCreated());

        MvcResult result = mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "S3cure-Passw0rd")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andReturn();
        Jwt jwt = jwtDecoder.decode(json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText());

        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "role", "iat", "exp");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(UUID.fromString(jwt.getSubject())).isNotNull();
    }

    @Test
    void passwordsAreStoredAsBcryptHashes() throws Exception {
        String username = uniqueName();
        register(username, "S3cure-Passw0rd").andExpect(status().isCreated());

        String stored = jdbc.queryForObject("SELECT password FROM users WHERE username = ?", String.class, username);

        assertThat(stored).startsWith("$2a$").doesNotContain("S3cure-Passw0rd");
    }

    @Test
    void duplicatedUsernameIsRejectedIgnoringCase() throws Exception {
        String username = uniqueName();
        register(username, "S3cure-Passw0rd").andExpect(status().isCreated());

        mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(username.toUpperCase(), "other-" + username + "@example.com", "S3cure-Passw0rd")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    void invalidRegistrationReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-ID", "corr-validation-1")
                        .content(registerBody("a", "not-an-email", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.correlationId").value("corr-validation-1"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("username")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")));
    }

    @Test
    void passwordsLongerThan72BytesAreRejectedCleanlyNot500() throws Exception {
        // 72 caracteres (pasa @Size) pero 144 bytes en UTF-8: BCrypt no puede procesarla
        String multibyte = "ñ".repeat(72);

        mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(uniqueName(), uniqueName() + "@example.com", multibyte)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));
        mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("admin", multibyte)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() throws Exception {
        String username = uniqueName();
        register(username, "S3cure-Passw0rd").andExpect(status().isCreated());

        mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("ghost-" + username, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void requestsWithoutValidTokenGetA401InPlatformFormat() throws Exception {
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.correlationId", notNullValue()));

        String forged = TestJwts.token("forged-secret-forged-secret-forged-secret-000", TestJwts.ISSUER,
                UUID.randomUUID().toString(), "ADMIN", java.time.Duration.ofMinutes(5));
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void listingUsersRequiresAdminRole() throws Exception {
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + TestJwts.user(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String adminToken = login("admin", "test-admin-password");
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].username", hasItem("admin")));
    }

    @Test
    void usersCannotReadOtherUsersButAdminsCan() throws Exception {
        String username = uniqueName();
        String id = json.readTree(register(username, "S3cure-Passw0rd").andReturn().getResponse().getContentAsString())
                .get("id").asText();

        mvc.perform(get("/api/users/" + id).header("Authorization", "Bearer " + TestJwts.user(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/users/" + id).header("Authorization", "Bearer " + login("admin", "test-admin-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
    }

    @Test
    void disabledUsersCannotLogIn() throws Exception {
        String username = uniqueName();
        String id = json.readTree(register(username, "S3cure-Passw0rd").andReturn().getResponse().getContentAsString())
                .get("id").asText();

        mvc.perform(patch("/api/users/" + id).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + login("admin", "test-admin-password"))
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, "S3cure-Passw0rd")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void flywayCreatedTheSchema() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version = '1'", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions register(String username, String password)
            throws Exception {
        return mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(username, username + "@example.com", password)));
    }

    private String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return node.get("accessToken").asText();
    }

    private String registerBody(String username, String email, String password) throws Exception {
        return json.writeValueAsString(java.util.Map.of("username", username, "email", email, "password", password));
    }

    private String loginBody(String username, String password) throws Exception {
        return json.writeValueAsString(java.util.Map.of("username", username, "password", password));
    }

    private static String uniqueName() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
