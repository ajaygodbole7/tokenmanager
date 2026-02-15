package org.tokenmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for advanced grant types (authorization_code, refresh_token)
 * against a real Keycloak instance.
 */
@Tag("integration")
class TokenManagerGrantTypeIT {

    @Test
    void shouldRetrieveTokenWithAuthorizationCode() throws IOException {
        String authCode = KeycloakTestSupport.obtainAuthorizationCode(
                KeycloakTestSupport.AUTHCODE_CLIENT_ID,
                KeycloakTestSupport.AUTHCODE_REDIRECT_URI,
                KeycloakTestSupport.TEST_USERNAME,
                KeycloakTestSupport.TEST_PASSWORD);

        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.AUTHCODE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.AUTHCODE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.AUTHORIZATION_CODE)
                .authorizationCode(authCode)
                .redirectUri(KeycloakTestSupport.AUTHCODE_REDIRECT_URI)
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
                KeycloakTestSupport.PASSWORD_CLIENT_ID,
                KeycloakTestSupport.PASSWORD_CLIENT_SECRET,
                KeycloakTestSupport.TEST_USERNAME,
                KeycloakTestSupport.TEST_PASSWORD);

        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.PASSWORD_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.PASSWORD_CLIENT_SECRET)
                .grantType(OAuth2GrantType.REFRESH_TOKEN)
                .refreshToken(refreshToken)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            String token = manager.getToken();
            assertThat(token).isNotNull().contains(".");
        }
    }

    @Test
    void shouldRejectReusedAuthorizationCode() throws IOException {
        String authCode = KeycloakTestSupport.obtainAuthorizationCode(
                KeycloakTestSupport.AUTHCODE_CLIENT_ID,
                KeycloakTestSupport.AUTHCODE_REDIRECT_URI,
                KeycloakTestSupport.TEST_USERNAME,
                KeycloakTestSupport.TEST_PASSWORD);

        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.AUTHCODE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.AUTHCODE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.AUTHORIZATION_CODE)
                .authorizationCode(authCode)
                .redirectUri(KeycloakTestSupport.AUTHCODE_REDIRECT_URI)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        // First use succeeds
        try (var manager = new OAuth2TokenManager(config)) {
            assertThat(manager.getToken()).isNotNull();
        }

        // Second use — same code — Keycloak returns invalid_grant
        try (var manager2 = new OAuth2TokenManager(config)) {
            assertThatThrownBy(manager2::getToken)
                    .isInstanceOf(InvalidCredentialsException.class);
        }
    }
}
