package org.tokenmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for core TokenManager behavior against a real Keycloak instance.
 * Covers client_credentials, password grant, caching, refresh, client auth methods, and errors.
 */
@Tag("integration")
@TestMethodOrder(OrderAnnotation.class)
class TokenManagerIT {

    @Test
    @Order(1)
    void keycloakRealmShouldBeConfigured() throws Exception {
        Request request = new Request.Builder()
                .url(KeycloakTestSupport.KEYCLOAK.getAuthServerUrl()
                        + "/realms/test-realm/.well-known/openid-configuration")
                .build();
        try (Response response = KeycloakTestSupport.HTTP_CLIENT
                .newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);

            JsonNode config = new ObjectMapper().readTree(response.body().string());
            assertThat(config.get("issuer").asText())
                    .endsWith("/realms/test-realm");
            assertThat(config.get("token_endpoint").asText())
                    .isEqualTo(KeycloakTestSupport.TOKEN_ENDPOINT);
        }
    }

    @Test
    void shouldRetrieveTokenWithClientCredentials() {
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.SERVICE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            String token = manager.getToken();
            assertThat(token).isNotNull().contains(".");
        }
    }

    @Test
    void shouldReturnValidJwtIssuedByTestRealm() throws Exception {
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.SERVICE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            String token = manager.getToken();

            // Structural: 3-part JWT (header.payload.signature)
            String[] parts = token.split("\\.");
            assertThat(parts).as("Token should be a 3-part JWT").hasSize(3);

            // Decode payload — no signature verification, that's the consumer's job
            String payload = new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode claims = new ObjectMapper().readTree(payload);

            assertThat(claims.get("iss").asText())
                    .endsWith("/realms/test-realm");
            assertThat(claims.has("exp")).isTrue();
            assertThat(claims.get("exp").asLong())
                    .isGreaterThan(Instant.now().getEpochSecond());
        }
    }

    @Test
    void shouldRetrieveTokenWithPasswordGrant() {
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.PASSWORD_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.PASSWORD_CLIENT_SECRET)
                .grantType(OAuth2GrantType.PASSWORD)
                .username(KeycloakTestSupport.TEST_USERNAME)
                .password(KeycloakTestSupport.TEST_PASSWORD)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            String token = manager.getToken();
            assertThat(token).isNotNull().contains(".");
        }
    }

    @Test
    void shouldThrowInvalidCredentialsForWrongClientSecret() {
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret("wrong-secret")
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            assertThatThrownBy(manager::getToken)
                    .isInstanceOf(InvalidCredentialsException.class);
        }
    }

    @Test
    void shouldThrowInvalidCredentialsForWrongPassword() {
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.PASSWORD_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.PASSWORD_CLIENT_SECRET)
                .grantType(OAuth2GrantType.PASSWORD)
                .username(KeycloakTestSupport.TEST_USERNAME)
                .password("wrong-password")
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            assertThatThrownBy(manager::getToken)
                    .isInstanceOf(InvalidCredentialsException.class);
        }
    }

    @Test
    void shouldCacheTokenAcrossMultipleCalls() {
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.SERVICE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            String first = manager.getToken();
            String second = manager.getToken();
            assertThat(first).isEqualTo(second);
        }
    }

    @Test
    void shouldRefreshTokenAfterExpiry() {
        MutableClock clock = new MutableClock(Instant.now());

        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.SERVICE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .refreshThreshold(Duration.ofSeconds(5))
                .clock(clock)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            String initial = manager.getToken();
            assertThat(initial).isNotNull();

            // Advance clock past realm's accessTokenLifespan + threshold
            clock.advance(Duration.ofSeconds(
                    KeycloakTestSupport.REALM_TOKEN_LIFETIME_SECONDS + 6));

            String refreshed = manager.getToken();
            assertThat(refreshed).isNotNull().isNotEqualTo(initial);
        }
    }

    @Test
    void shouldWorkWithClientSecretBasic() {
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.SERVICE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .clientAuthMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            String token = manager.getToken();
            assertThat(token).isNotNull().contains(".");
        }
    }

    @Test
    void shouldRejectInvalidScope() {
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.SERVICE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .scope(java.util.Set.of("nonexistent-scope"))
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            assertThatThrownBy(manager::getToken)
                    .isInstanceOf(InvalidConfigurationException.class);
        }
    }

    @Test
    void shouldRejectPasswordGrantOnClientCredentialsOnlyClient() {
        // test-service-client has directAccessGrantsEnabled: false — Keycloak returns
        // unauthorized_client, classified as InvalidCredentialsException
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.SERVICE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.PASSWORD)
                .username(KeycloakTestSupport.TEST_USERNAME)
                .password(KeycloakTestSupport.TEST_PASSWORD)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            assertThatThrownBy(manager::getToken)
                    .isInstanceOf(InvalidCredentialsException.class);
        }
    }

    @Test
    void shouldHandleWrongEndpointOnRealServer() {
        // Valid Keycloak host, wrong path — Keycloak returns 405, falls through to
        // fallbackToHttpStatusHandling as InvalidEndpointException
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.KEYCLOAK.getAuthServerUrl()
                        + "/realms/test-realm/protocol/openid-connect/wrong-path")
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.SERVICE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            assertThatThrownBy(manager::getToken)
                    .isInstanceOf(InvalidEndpointException.class);
        }
    }

    @Test
    void shouldReturnCachedTokenWhenServerBecomesUnavailable() throws Exception {
        // Short-timeout client so the paused container times out quickly
        OkHttpClient shortTimeoutClient =
                KeycloakTestSupport.createTrustAllClient(Duration.ofSeconds(2));
        MutableClock clock = new MutableClock(Instant.now());

        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.SERVICE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .httpClient(shortTimeoutClient)
                .httpTimeout(Duration.ofSeconds(2))
                .maxRetryAttempts(1)
                .refreshThreshold(Duration.ofSeconds(5))
                .clock(clock)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            // Step 1: Obtain and cache a valid token
            String initial = manager.getToken();
            assertThat(initial).isNotNull();

            // Step 2: Advance clock past refresh threshold but before absolute expiry.
            // Token lifetime is 60s, threshold is 5s → advance to 57s leaves 3s of validity.
            clock.advance(Duration.ofSeconds(
                    KeycloakTestSupport.REALM_TOKEN_LIFETIME_SECONDS - 3));

            // Step 3: Freeze Keycloak — TCP connections will hang until read timeout
            KeycloakTestSupport.pauseKeycloak();
            try {
                // Step 4: getToken() triggers refresh (past threshold), refresh times out
                // → ServiceUnavailableException → graceful degradation returns cached token
                String degraded = manager.getToken();
                assertThat(degraded)
                        .as("Should return cached token during server outage")
                        .isEqualTo(initial);
            } finally {
                KeycloakTestSupport.unpauseKeycloak();
            }
        }
    }
}
