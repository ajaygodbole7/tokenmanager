package org.tokenmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for core TokenManager behavior against a real Keycloak instance.
 * Covers client_credentials, password grant, caching, refresh, client auth methods, and errors.
 */
class TokenManagerIT {

    @Test
    void shouldRetrieveTokenWithClientCredentials() {
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId("test-service-client")
                .clientSecret("test-service-secret")
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            String token = manager.getToken();
            assertThat(token).isNotNull().contains(".");
        }
    }

    @Test
    void shouldRetrieveTokenWithPasswordGrant() {
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId("test-password-client")
                .clientSecret("test-password-secret")
                .grantType(OAuth2GrantType.PASSWORD)
                .username("testuser")
                .password("testpass")
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
                .clientId("test-service-client")
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
                .clientId("test-password-client")
                .clientSecret("test-password-secret")
                .grantType(OAuth2GrantType.PASSWORD)
                .username("testuser")
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
                .clientId("test-service-client")
                .clientSecret("test-service-secret")
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
                .clientId("test-service-client")
                .clientSecret("test-service-secret")
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .refreshThreshold(Duration.ofSeconds(5))
                .clock(clock)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            String initial = manager.getToken();
            assertThat(initial).isNotNull();

            // Advance clock past the realm's 60s accessTokenLifespan + threshold
            clock.advance(Duration.ofSeconds(66));

            String refreshed = manager.getToken();
            assertThat(refreshed).isNotNull().isNotEqualTo(initial);
        }
    }

    @Test
    void shouldWorkWithClientSecretBasic() {
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId("test-service-client")
                .clientSecret("test-service-secret")
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
                .clientId("test-service-client")
                .clientSecret("test-service-secret")
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .scope(java.util.Set.of("nonexistent-scope"))
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            assertThatThrownBy(manager::getToken)
                    .isInstanceOf(InvalidConfigurationException.class);
        }
    }
}
