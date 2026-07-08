/*
 * Copyright 2026 ajaygodbole7
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ajaygodbole7.tokenmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
        // OIDC discovery endpoint returns correct issuer and token_endpoint for the test realm
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
        // client_credentials grant returns a valid access token
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
        // Token is a 3-part JWT with correct issuer and a future expiry claim
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
        // Resource Owner Password Credentials grant returns a valid access token
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
        // Wrong client_secret → Keycloak returns unauthorized_client → InvalidCredentialsException
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
        // Wrong user password → Keycloak returns invalid_grant → InvalidCredentialsException
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
        // Consecutive getToken() calls return the same cached token without a network round-trip
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
        // After clock advances past expiry + threshold, getToken() fetches a new token from the server
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
    void shouldExerciseFullTokenLifecycleAgainstKeycloak() throws Exception {
        // One end-to-end pass through the manager's lifecycle against real Keycloak:
        // cold fetch -> cache hit -> proactive refresh after expiry -> invalidate-forced
        // exchange -> async cache hit -> close -> post-close rejection.
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

        OAuth2TokenManager manager = new OAuth2TokenManager(config);
        try {
            // 1. Cold fetch — a real JWT issued by the realm.
            String initial = manager.getToken();
            assertThat(initial).isNotNull().contains(".");

            // 2. Cache hit — the same token, no server round-trip.
            assertThat(manager.getToken()).isEqualTo(initial);

            // 3. Proactive refresh once the clock passes expiry + threshold.
            clock.advance(Duration.ofSeconds(
                    KeycloakTestSupport.REALM_TOKEN_LIFETIME_SECONDS + 6));
            String refreshed = manager.getToken();
            assertThat(refreshed).isNotNull().isNotEqualTo(initial);

            // 4. invalidate() forces a fresh exchange even though the token is still valid.
            manager.invalidate();
            String afterInvalidate = manager.getToken();
            assertThat(afterInvalidate).isNotNull().isNotEqualTo(refreshed);

            // 5. The async path serves the freshly cached token.
            assertThat(manager.getTokenAsync().get()).isEqualTo(afterInvalidate);

            // 6. After close(), further calls are rejected on both APIs.
            manager.close();
            assertThatThrownBy(manager::getToken)
                    .isInstanceOf(IllegalStateException.class);
            assertThat(manager.getTokenAsync()).isCompletedExceptionally();
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldWorkWithClientSecretBasic() {
        // CLIENT_SECRET_BASIC sends credentials in the Authorization header and obtains a token
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
        // Requesting a scope not configured on the client → InvalidConfigurationException
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
        // Graceful degradation: docker pause freezes Keycloak, refresh times out, cached token returned
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

    @Test
    void shouldRecoverCircuitBreakerAfterKeycloakOutageEnds() throws Exception {
        // End-to-end proof of circuit breaker OPEN -> HALF_OPEN -> CLOSED recovery
        // against a real Keycloak instance (not just MockWebServer).
        OkHttpClient shortTimeoutClient =
                KeycloakTestSupport.createTrustAllClient(Duration.ofSeconds(2));

        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.SERVICE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .httpClient(shortTimeoutClient)
                .httpTimeout(Duration.ofSeconds(2))
                .maxRetryAttempts(1)
                .circuitBreakerMinimumCalls(2)
                .circuitBreakerWaitDuration(Duration.ofSeconds(3))
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            // Warm-up: prove connectivity and seed one success before the outage,
            // exercising the "prior success then consecutive failures still opens
            // the breaker" path (the sliding-window fix — see OAuth2TokenManager
            // createCircuitBreaker()).
            String warmup = manager.getToken();
            assertThat(warmup).isNotNull();

            // The warmed-up token is still well within its validity window (realm
            // lifetime 60s), so without invalidating it the calls below would be
            // served from cache with no network round-trip at all, never touching
            // the paused server. invalidate() forces every subsequent getToken()
            // call to actually attempt a refresh.
            manager.invalidate();

            KeycloakTestSupport.pauseKeycloak();
            try {
                // Two consecutive failures (blocked by the 2s read timeout while
                // the container is frozen) fill the sliding window and open the
                // circuit breaker.
                for (int i = 0; i < 2; i++) {
                    assertThatThrownBy(manager::getToken)
                            .isInstanceOf(ServiceUnavailableException.class);
                }

                // A third call while still paused must fast-fail — proof the
                // breaker is OPEN and short-circuiting rather than attempting
                // another network call and waiting out the read timeout.
                long start = System.nanoTime();
                assertThatThrownBy(manager::getToken)
                        .isInstanceOf(ServiceUnavailableException.class);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertThat(elapsedMs)
                        .as("Open breaker should fast-fail without waiting on the network")
                        .isLessThan(500);

                // Wait out the circuit breaker's open-state window.
                Thread.sleep(3500);
            } finally {
                KeycloakTestSupport.unpauseKeycloak();
            }

            // Keycloak is unpaused (guaranteed by the finally block above,
            // regardless of whether any assertion above failed). The breaker
            // should now recover: a probe call reaches the real server again
            // and succeeds, proving the OPEN -> HALF_OPEN -> CLOSED transition
            // against a live Keycloak instance.
            String recovered = manager.getToken();
            assertThat(recovered).isNotNull();
        }
    }
}
