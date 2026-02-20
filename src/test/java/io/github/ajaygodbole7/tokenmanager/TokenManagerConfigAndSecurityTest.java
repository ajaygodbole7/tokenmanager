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

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenManagerConfigAndSecurityTest extends AbstractMockServerTest {

  // --- TokenConfig validation tests ---

  @Test
  void shouldRejectBlankClientId() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId(" ")
        .clientSecret("secret")
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("clientId");
  }

  @Test
  void shouldRejectBlankTokenEndpoint() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint(" ")
        .clientId("client")
        .clientSecret("secret")
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tokenEndpoint");
  }

  @Test
  void shouldDefensivelyCopyScope() {
    Set<String> mutable = new HashSet<>(Set.of("read"));
    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .scope(mutable)
        .build();
    mutable.add("write");
    assertThat(config.getScope()).hasSize(1);
    assertThat(config.getScope()).contains("read");
  }

  @Test
  void shouldRejectNonHttpsEndpoint() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("http://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tokenEndpoint must use HTTPS");
  }

  @Test
  void shouldRejectZeroHttpTimeout() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .httpTimeout(Duration.ZERO)
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("httpTimeout must be positive");
  }

  @Test
  void shouldRejectNegativeHttpTimeout() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .httpTimeout(Duration.ofSeconds(-1))
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("httpTimeout must be positive");
  }

  @Test
  void shouldRejectZeroRefreshThreshold() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .refreshThreshold(Duration.ZERO)
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("refreshThreshold must be positive");
  }

  @Test
  void shouldRejectNegativeRefreshThreshold() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .refreshThreshold(Duration.ofSeconds(-5))
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("refreshThreshold must be positive");
  }

  @Test
  void shouldRejectPasswordGrantMissingUsername() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .grantType(OAuth2GrantType.PASSWORD)
        .password("pass")
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("username and password");
  }

  @Test
  void shouldRejectPasswordGrantMissingPassword() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .grantType(OAuth2GrantType.PASSWORD)
        .username("user")
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("username and password");
  }

  @Test
  void shouldRejectAuthorizationCodeGrantMissingCode() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .grantType(OAuth2GrantType.AUTHORIZATION_CODE)
        .redirectUri("https://example.com/callback")
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("code and redirect URI");
  }

  @Test
  void shouldRejectAuthorizationCodeGrantMissingRedirectUri() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .grantType(OAuth2GrantType.AUTHORIZATION_CODE)
        .authorizationCode("auth-code-123")
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("code and redirect URI");
  }

  @Test
  void shouldRejectRefreshTokenGrantMissingRefreshToken() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .grantType(OAuth2GrantType.REFRESH_TOKEN)
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("refresh token");
  }

  @Test
  void shouldRejectJwtBearerGrantMissingAssertion() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .grantType(OAuth2GrantType.JWT_BEARER)
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("assertion");
  }

  @Test
  void shouldRejectImplicitGrantType() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .grantType(OAuth2GrantType.IMPLICIT)
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Implicit grant type is not supported");
  }

  // --- Resilience4j config validation tests ---

  @Test
  void shouldRejectZeroMaxRetryAttempts() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .maxRetryAttempts(0)
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxRetryAttempts must be >= 1");
  }

  @Test
  void shouldRejectZeroInitialRetryDelay() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .initialRetryDelay(Duration.ZERO)
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("initialRetryDelay must be positive");
  }

  @Test
  void shouldRejectZeroCircuitBreakerMinimumCalls() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .circuitBreakerMinimumCalls(0)
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("circuitBreakerMinimumCalls must be >= 1");
  }

  @Test
  void shouldRejectZeroCircuitBreakerWaitDuration() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .clientId("client")
        .clientSecret("secret")
        .circuitBreakerWaitDuration(Duration.ZERO)
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("circuitBreakerWaitDuration must be positive");
  }

  // --- Null rejection tests ---

  @Test
  void shouldRejectNullClock() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/token")
        .clientId("client")
        .clientSecret("secret")
        .clock(null)
        .build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void shouldRejectNullClientAuthMethod() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/token")
        .clientId("client")
        .clientSecret("secret")
        .clientAuthMethod(null)
        .build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void shouldRejectNullScope() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/token")
        .clientId("client")
        .clientSecret("secret")
        .scope(null)
        .build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void shouldRejectNullGrantType() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/token")
        .clientId("client")
        .clientSecret("secret")
        .grantType(null)
        .build())
        .isInstanceOf(NullPointerException.class);
  }

  // --- Redirect tests ---

  @Test
  void shouldNotFollowRedirects() throws Exception {
    // Verifies that a 302 redirect is treated as an error, not followed.
    // A redirect from a token endpoint could send credentials to an untrusted host.
    // Uses a no-redirect httpClient to simulate the internally-created client's behavior.
    OkHttpClient noRedirectClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build();

    TokenConfig noRedirectConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("redirect-test-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(noRedirectClient)
        .build();

    OAuth2TokenManager mgr = new OAuth2TokenManager(noRedirectConfig);
    try {
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(302)
          .addHeader("Location", mockWebServer.url(TOKEN_ENDPOINT).toString()));

      assertThatThrownBy(mgr::getToken)
          .isInstanceOf(InvalidEndpointException.class);
    } finally {
      mgr.close();
    }
  }

  // --- Security tests ---

  @Test
  void toStringShouldNotContainSecrets() {
    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/token")
        .clientId("my-client")
        .clientSecret("super-secret-value")
        .grantType(OAuth2GrantType.PASSWORD)
        .username("user")
        .password("hunter2")
        .build();

    String str = config.toString();
    assertThat(str).contains("my-client");
    assertThat(str).contains("user");
    assertThat(str).doesNotContain("super-secret-value");
    assertThat(str).doesNotContain("hunter2");
  }

  @Test
  void tokenToStringShouldNotContainTokenValue() {
    OAuth2Token token = new OAuth2Token(
        "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.secret-payload",
        OAuth2TokenType.BEARER,
        Instant.now(),
        Instant.now().plusSeconds(3600),
        Set.of("read"));

    String str = token.toString();
    assertThat(str).doesNotContain("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9");
    assertThat(str).doesNotContain("secret-payload");
    assertThat(str).contains("Bearer");
    assertThat(str).contains("read");
  }

  @Test
  void exceptionMessagesShouldNotContainSecrets() throws Exception {
    String secretClientSecret = "super-secret-credential-value";

    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("exc-test-" + UUID.randomUUID())
        .clientSecret(secretClientSecret)
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(config);

    try {
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(401)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "error": "invalid_client",
                  "error_description": "Bad credentials"
              }
              """));

      assertThatThrownBy(manager::getToken)
          .isInstanceOf(TokenException.class)
          .satisfies(e -> {
            assertThat(e.getMessage()).doesNotContain(secretClientSecret);
            if (e.getCause() != null) {
              assertThat(e.getCause().getMessage()).doesNotContain(secretClientSecret);
            }
          });
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldNotCloseUserProvidedHttpClient() {
    OkHttpClient customClient = new OkHttpClient.Builder().build();
    String endpoint = mockWebServer.url("/token").toString();

    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint(endpoint)
        .clientId("custom-client-lifecycle-" + UUID.randomUUID())
        .clientSecret("secret")
        .httpClient(customClient)
        .build();
    config.validate();

    OAuth2TokenManager manager = new OAuth2TokenManager(config);
    manager.close();

    assertThat(customClient.dispatcher().executorService().isShutdown()).isFalse();
  }
}
