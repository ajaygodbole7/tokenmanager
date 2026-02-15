package org.tokenmanager;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for advanced grant types (authorization_code, refresh_token)
 * against a real Keycloak instance.
 */
class TokenManagerGrantTypeIT {

    @Test
    void shouldRetrieveTokenWithAuthorizationCode() throws IOException {
        String authCode = KeycloakTestSupport.obtainAuthorizationCode(
                "test-authcode-client",
                "http://localhost/callback",
                "testuser",
                "testpass");

        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId("test-authcode-client")
                .clientSecret("test-authcode-secret")
                .grantType(OAuth2GrantType.AUTHORIZATION_CODE)
                .authorizationCode(authCode)
                .redirectUri("http://localhost/callback")
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            String token = manager.getToken();
            assertThat(token).isNotNull().contains(".");
        }
    }

    @Test
    void shouldRetrieveTokenWithRefreshToken() throws IOException {
        String refreshToken = KeycloakTestSupport.obtainRefreshToken(
                "test-password-client",
                "test-password-secret",
                "testuser",
                "testpass");

        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId("test-password-client")
                .clientSecret("test-password-secret")
                .grantType(OAuth2GrantType.REFRESH_TOKEN)
                .refreshToken(refreshToken)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            String token = manager.getToken();
            assertThat(token).isNotNull().contains(".");
        }
    }
}
