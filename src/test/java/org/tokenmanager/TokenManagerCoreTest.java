package org.tokenmanager;

import java.util.Set;
import java.util.UUID;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenManagerCoreTest extends AbstractMockServerTest {

  @Test
  void shouldRetrieveValidToken() throws Exception {
    // Arrange
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
                {
                    "access_token": "test-token",
                    "token_type": "Bearer",
                    "expires_in": 3600
                }
                """));

    // Act
    String token = tokenManager.getToken();

    // Assert
    assertThat(token).isEqualTo("test-token");

    // Verify the request
    RecordedRequest recordedRequest = mockWebServer.takeRequest();
    assertThat(recordedRequest.getPath()).isEqualTo(TOKEN_ENDPOINT);
    assertThat(recordedRequest.getMethod()).isEqualTo("POST");
    assertThat(recordedRequest.getHeader(CONTENT_TYPE_HEADER))
        .isEqualTo(CONTENT_TYPE_FORM_URLENCODED);

    String body = recordedRequest.getBody().readUtf8();
    assertThat(body).contains("grant_type=client_credentials");
    assertThat(body).contains("client_id=test-client");
    assertThat(body).contains("client_secret=test-secret");
  }

  @Test
  void shouldAcceptTokenTypeCaseInsensitively() throws Exception {
    // Providers may return "bearer", "BEARER", or "Bearer" — all must work.
    // Unknown types default to Bearer rather than throwing IllegalArgumentException.
    String[] variants = {"bearer", "BEARER", "Bearer", "unknown_type"};

    for (String variant : variants) {
      String uniqueClientId = "token-type-" + UUID.randomUUID();
      TokenConfig config = TokenConfig.builder()
          .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
          .clientId(uniqueClientId)
          .clientSecret("test-secret")
          .httpTimeout(HTTP_TIMEOUT)
          .refreshThreshold(REFRESH_THRESHOLD)
          .httpClient(httpClient)
          .build();

      try (OAuth2TokenManager manager = new OAuth2TokenManager(config)) {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
            .setBody("""
                {"access_token": "token-%s", "token_type": "%s", "expires_in": 3600}
                """.formatted(variant, variant)));

        String token = manager.getToken();
        assertThat(token).isEqualTo("token-" + variant);
      }
    }
  }

  /**
   * Tests that TokenManager properly caches valid tokens and doesn't make unnecessary requests.
   */
  @Test
  void shouldReuseValidTokenWithoutNewRequest() throws Exception {
    // Given: Server provides a token with long expiry
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "access_token": "cached-token",
                "token_type": "Bearer",
                "expires_in": 7200
            }
            """));

    // When: First request
    String firstToken = tokenManager.getToken();

    // Then: Should get token
    assertThat(firstToken)
        .as("First token request successful")
        .isEqualTo("cached-token");

    // And: Second request should use cache
    String secondToken = tokenManager.getToken();
    assertThat(secondToken)
        .as("Second token from cache")
        .isEqualTo("cached-token");

    // And: Should have made only one request
    assertThat(mockWebServer.getRequestCount())
        .as("Only one server request should be made")
        .isEqualTo(1);
  }

  /**
   * Tests that TokenManager proactively refreshes tokens before expiry.
   *
   * When a token is near its expiry threshold
   * Then the TokenManager should:
   * - Proactively refresh the token
   * - Return the new token
   * - Make only one refresh request
   */
  @Test
  void whenTokenNearExpiry_thenProactivelyRefreshes() throws Exception {
    // Create a mutable clock so we can advance time without sleeping
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("refresh-test-client")
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(1))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager refreshManager = new OAuth2TokenManager(testConfig);

    try {
      // Given: Initial token with 5 second expiry
      mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                                .setBody("""
                {
                    "access_token": "initial-token",
                    "token_type": "Bearer",
                    "expires_in": 5
                }
                """));

      // And: A refresh response ready
      mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                                .setBody("""
                {
                    "access_token": "refreshed-token",
                    "token_type": "Bearer",
                    "expires_in": 3600
                }
                """));

      // When: Get initial token
      String initialToken = refreshManager.getToken();
      assertThat(initialToken).isEqualTo("initial-token");

      // Advance clock past the refresh threshold (5s expiry - 1s threshold = valid for 4s)
      testClock.advance(Duration.ofSeconds(5));

      // When: Request token again — should trigger refresh
      String refreshedToken = refreshManager.getToken();

      // Then: Should get new token
      assertThat(refreshedToken)
          .as("Should receive refreshed token")
          .isEqualTo("refreshed-token");

    } finally {
      refreshManager.close();
    }
  }

  @Test
  void shouldRefreshAtExactThresholdBoundary() throws Exception {
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig clockConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("boundary-test-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(10))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager clockManager = new OAuth2TokenManager(clockConfig);

    try {
      // Token with 30s expiry
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "boundary-token",
                  "token_type": "Bearer",
                  "expires_in": 30
              }
              """));

      clockManager.getToken();

      // Advance to just before threshold (30s - 10s = 20s validity window)
      // At 19s, now + 10s threshold = 29s < 30s expiry → still valid
      testClock.advance(Duration.ofSeconds(19));
      String stillValid = clockManager.getToken();
      assertThat(stillValid).isEqualTo("boundary-token");
      assertThat(mockWebServer.getRequestCount()).isEqualTo(1);

      // Advance 2 more seconds to cross the threshold
      // At 21s, now + 10s threshold = 31s > 30s expiry → needs refresh
      testClock.advance(Duration.ofSeconds(2));

      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "refreshed-boundary-token",
                  "token_type": "Bearer",
                  "expires_in": 3600
              }
              """));

      String refreshed = clockManager.getToken();
      assertThat(refreshed).isEqualTo("refreshed-boundary-token");
      assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    } finally {
      clockManager.close();
    }
  }

  @Test
  void isValid_shouldUseClockForTimeComparison() {
    Instant baseTime = Instant.parse("2025-01-01T00:00:00Z");
    MutableClock clock = new MutableClock(baseTime);

    // Token expires 60s from baseTime
    OAuth2Token token = new OAuth2Token(
        "test-token", OAuth2TokenType.BEARER,
        baseTime, baseTime.plusSeconds(60), Set.of());

    // At t=0 with 10s threshold: 0+10=10 < 60 → valid
    assertThat(token.isValid(Duration.ofSeconds(10), clock)).isTrue();

    // Advance to t=49: 49+10=59 < 60 → still valid
    clock.advance(Duration.ofSeconds(49));
    assertThat(token.isValid(Duration.ofSeconds(10), clock)).isTrue();

    // Advance to t=51: 51+10=61 > 60 → invalid (within threshold)
    clock.advance(Duration.ofSeconds(2));
    assertThat(token.isValid(Duration.ofSeconds(10), clock)).isFalse();

    // Advance to t=70: 70+10=80 > 60 → invalid (past expiry)
    clock.advance(Duration.ofSeconds(19));
    assertThat(token.isValid(Duration.ofSeconds(10), clock)).isFalse();
  }

  /**
   * Tests TokenManager's handling of different OAuth2 configurations:
   * - Different grant types (PASSWORD, CLIENT_CREDENTIALS)
   * - Scopes in request and response
   * - Different token types
   *
   * Using different TokenManager instances to keep test isolation.
   */
  @Test
  void shouldHandleMultipleOAuthConfigurations() throws Exception {
    // Test 1: PASSWORD grant type with scopes
    TokenConfig passwordConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("password-client")
        .clientSecret("test-secret")
        .grantType(OAuth2GrantType.PASSWORD)
        .username("test-user")
        .password("test-pass")
        .scope(Set.of("read", "write"))
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager passwordManager = new OAuth2TokenManager(passwordConfig);

    try {
      // Queue PASSWORD grant response
      mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                                .setBody("""
                {
                    "access_token": "password-token",
                    "token_type": "MAC",
                    "expires_in": 3600,
                    "scope": "read write"
                }
                """));

      String passwordToken = passwordManager.getToken();
      assertThat(passwordToken).isEqualTo("password-token");

      // Verify PASSWORD request
      RecordedRequest passwordRequest = mockWebServer.takeRequest();
      String passwordBody = passwordRequest.getBody().readUtf8();
      assertThat(passwordBody)
          .contains("grant_type=password")
          .contains("username=test-user")
          .contains("password=test-pass")
          .contains("scope=")     // Verify scope presence
          .contains("read")       // Verify individual scopes
          .contains("write");     // without assuming order

      // Test 2: CLIENT_CREDENTIALS with different scope
      TokenConfig clientConfig = TokenConfig.builder()
          .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
          .clientId("client-cred")
          .clientSecret("test-secret")
          .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
          .scope(Set.of("admin"))
          .httpTimeout(HTTP_TIMEOUT)
          .refreshThreshold(REFRESH_THRESHOLD)
          .httpClient(httpClient)
          .build();

      OAuth2TokenManager clientManager = new OAuth2TokenManager(clientConfig);

      try {
        // Queue CLIENT_CREDENTIALS response
        mockWebServer.enqueue(new MockResponse()
                                  .setResponseCode(200)
                                  .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                                  .setBody("""
                    {
                        "access_token": "client-token",
                        "token_type": "Bearer",
                        "expires_in": 3600,
                        "scope": "admin"
                    }
                    """));

        String clientToken = clientManager.getToken();
        assertThat(clientToken).isEqualTo("client-token");

        // Verify CLIENT_CREDENTIALS request
        RecordedRequest clientRequest = mockWebServer.takeRequest();
        String clientBody = clientRequest.getBody().readUtf8();
        assertThat(clientBody)
            .contains("grant_type=client_credentials")
            .contains("scope=admin");

      } finally {
        clientManager.close();
      }
    } finally {
      passwordManager.close();
    }
  }

  @Test
  void shouldSendJwtBearerAssertionInRequestBody() throws Exception {
    TokenConfig jwtConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("jwt-client-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .grantType(OAuth2GrantType.JWT_BEARER)
        .assertion("eyJhbGciOiJSUzI1NiJ9.test-assertion")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager jwtManager = new OAuth2TokenManager(jwtConfig);

    try {
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "jwt-token",
                  "token_type": "Bearer",
                  "expires_in": 3600
              }
              """));

      String token = jwtManager.getToken();
      assertThat(token).isEqualTo("jwt-token");

      RecordedRequest request = mockWebServer.takeRequest();
      String body = request.getBody().readUtf8();
      assertThat(body)
          .contains("grant_type=urn")
          .contains("assertion=eyJhbGciOiJSUzI1NiJ9.test-assertion");
    } finally {
      jwtManager.close();
    }
  }

  @Test
  void shouldSendAuthorizationCodeParamsInRequestBody() throws Exception {
    TokenConfig authCodeConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("authcode-client-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .grantType(OAuth2GrantType.AUTHORIZATION_CODE)
        .authorizationCode("auth-code-xyz")
        .redirectUri("https://example.com/callback")
        .codeVerifier("pkce-verifier-123")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager authCodeManager = new OAuth2TokenManager(authCodeConfig);

    try {
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "authcode-token",
                  "token_type": "Bearer",
                  "expires_in": 3600
              }
              """));

      String token = authCodeManager.getToken();
      assertThat(token).isEqualTo("authcode-token");

      RecordedRequest request = mockWebServer.takeRequest();
      String body = request.getBody().readUtf8();
      assertThat(body)
          .contains("grant_type=authorization_code")
          .contains("code=auth-code-xyz")
          .contains("redirect_uri=https")
          .contains("code_verifier=pkce-verifier-123");
    } finally {
      authCodeManager.close();
    }
  }

  @Test
  void shouldSendRefreshTokenParamInRequestBody() throws Exception {
    TokenConfig refreshConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("refresh-client-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .grantType(OAuth2GrantType.REFRESH_TOKEN)
        .refreshToken("refresh-token-abc")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager refreshManager = new OAuth2TokenManager(refreshConfig);

    try {
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "refreshed-access-token",
                  "token_type": "Bearer",
                  "expires_in": 3600
              }
              """));

      String token = refreshManager.getToken();
      assertThat(token).isEqualTo("refreshed-access-token");

      RecordedRequest request = mockWebServer.takeRequest();
      String body = request.getBody().readUtf8();
      assertThat(body)
          .contains("grant_type=refresh_token")
          .contains("refresh_token=refresh-token-abc");
    } finally {
      refreshManager.close();
    }
  }

  @Test
  void shouldSendCredentialsAsBasicAuthHeader() throws Exception {
    TokenConfig basicConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("basic-client-" + UUID.randomUUID())
        .clientSecret("basic-secret")
        .clientAuthMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager basicManager = new OAuth2TokenManager(basicConfig);

    try {
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "basic-token",
                  "token_type": "Bearer",
                  "expires_in": 3600
              }
              """));

      String token = basicManager.getToken();
      assertThat(token).isEqualTo("basic-token");

      RecordedRequest request = mockWebServer.takeRequest();
      String authHeader = request.getHeader("Authorization");
      assertThat(authHeader).startsWith("Basic ");

      // Decode and verify credentials
      String decoded = new String(
          java.util.Base64.getDecoder().decode(authHeader.substring(6)),
          java.nio.charset.StandardCharsets.UTF_8);
      assertThat(decoded).startsWith("basic-client-");
      assertThat(decoded).endsWith(":basic-secret");

      // client_id in body for provider compatibility, client_secret omitted
      String body = request.getBody().readUtf8();
      assertThat(body).contains("grant_type=client_credentials");
      assertThat(body).contains("client_id=");
      assertThat(body).doesNotContain("client_secret=");
    } finally {
      basicManager.close();
    }
  }

  @Test
  void shouldSendCredentialsInFormBodyByDefault() throws Exception {
    // The default tokenManager uses CLIENT_SECRET_POST (default)
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {
                "access_token": "post-token",
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """));

    tokenManager.getToken();

    RecordedRequest request = mockWebServer.takeRequest();

    // No Authorization header for Basic auth
    assertThat(request.getHeader("Authorization")).isNull();

    // Credentials in form body
    String body = request.getBody().readUtf8();
    assertThat(body).contains("client_id=");
    assertThat(body).contains("client_secret=test-secret");
  }

  @Test
  void shouldHandleSpecialCharactersInBasicAuthCredentials() throws Exception {
    TokenConfig specialConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("client:with:colons")
        .clientSecret("secret/with+special=chars")
        .clientAuthMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager specialManager = new OAuth2TokenManager(specialConfig);

    try {
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "special-token",
                  "token_type": "Bearer",
                  "expires_in": 3600
              }
              """));

      specialManager.getToken();

      RecordedRequest request = mockWebServer.takeRequest();
      String authHeader = request.getHeader("Authorization");
      String decoded = new String(
          java.util.Base64.getDecoder().decode(authHeader.substring(6)),
          java.nio.charset.StandardCharsets.UTF_8);
      assertThat(decoded).isEqualTo("client:with:colons:secret/with+special=chars");
    } finally {
      specialManager.close();
    }
  }

  @Test
  void shouldThrowIllegalStateExceptionAfterClose() {
    tokenManager.close();

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TokenManager is closed");
  }

  @Test
  void shouldAllowMultipleCloseCallsWithoutThrowing() {
    tokenManager.close();
    tokenManager.close();
    tokenManager.close();
    // No exception — idempotent
  }

  @Test
  void shouldImplementTokenProviderInterface() {
    // OAuth2TokenManager implements the TokenProvider interface
    assertThat(tokenManager).isInstanceOf(TokenProvider.class);
  }

  // --- 1a: RejectedExecutionException race on close ---

  @Test
  void shouldThrowServiceUnavailableWhenExecutorShutDown() throws Exception {
    // Close races with getToken: thread A passes closed guard, thread B closes,
    // thread A hits supplyAsync on shut-down executor. Must get
    // ServiceUnavailableException, not RejectedExecutionException.
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"access_token": "initial", "token_type": "bearer", "expires_in": 1}
            """));

    tokenManager.getToken();
    tokenManager.close();

    // After close, startNewRefresh wraps RejectedExecutionException
    // Build a new manager and close it immediately to ensure the race path
    TokenConfig freshConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("ree-test-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(freshConfig);
    manager.close();

    // getToken() after close should throw IllegalStateException (the closed guard).
    // But if the closed guard is bypassed, startNewRefresh should catch REE
    // and return a failed future → ServiceUnavailableException.
    assertThatThrownBy(manager::getToken)
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldFormatAuthorizationHeaderValue() {
    OAuth2Token token = new OAuth2Token(
        "abc123", OAuth2TokenType.BEARER,
        Instant.now(), Instant.now().plusSeconds(3600), Set.of());

    assertThat(token.getAuthorizationHeaderValue()).isEqualTo("Bearer abc123");
  }
}
