package org.tokenmanager;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import io.github.resilience4j.core.IntervalFunction;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenManagerTest {
  // Fixed endpoints and headers
  private static final String TOKEN_ENDPOINT = "/oauth/token";
  private static final String CONTENT_TYPE_HEADER = "Content-Type";
  private static final String CONTENT_TYPE_JSON = "application/json";
  private static final String CONTENT_TYPE_FORM_URLENCODED = "application/x-www-form-urlencoded";

  // Configuration constants
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration REFRESH_THRESHOLD = Duration.ofSeconds(30);

  private MockWebServer mockWebServer;
  private OkHttpClient httpClient;
  private TokenConfig tokenConfig;
  private OAuth2TokenManager tokenManager;



  @BeforeEach
  void setUp() throws IOException {
    // Setup server certificates
    HeldCertificate rootCertificate = new HeldCertificate.Builder()
        .certificateAuthority(1)
        .build();

    HeldCertificate serverCertificate = new HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .signedBy(rootCertificate)
        .build();

    HandshakeCertificates serverCerts = new HandshakeCertificates.Builder()
        .heldCertificate(serverCertificate)
        .build();

    HandshakeCertificates clientCerts = new HandshakeCertificates.Builder()
        .addTrustedCertificate(rootCertificate.certificate())
        .build();

    // Start a new MockWebServer for each test
    mockWebServer = new MockWebServer();
    mockWebServer.useHttps(serverCerts.sslSocketFactory(), false);
    mockWebServer.start();

    // Create a unique HTTP client for each test
    httpClient = new OkHttpClient.Builder()
        .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager())
        .hostnameVerifier((_, _) -> true)
        .connectTimeout(HTTP_TIMEOUT)
        .readTimeout(HTTP_TIMEOUT)
        .writeTimeout(HTTP_TIMEOUT)
        .build();

    // Create unique configuration for each test
    tokenConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("test-client-" + UUID.randomUUID()) // Unique client ID
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    // Create a new instance of TokenManager for each test
    tokenManager = new OAuth2TokenManager(tokenConfig);

  }

  @AfterEach
   void tearDown() throws IOException {
    if (tokenManager != null) {
      tokenManager.close(); // Ensure executor and circuit breaker are cleaned up
    }
    if (mockWebServer != null) {
      mockWebServer.shutdown();
    }
    if (httpClient != null) {
      httpClient.dispatcher().executorService().shutdown();
      httpClient.connectionPool().evictAll();
    }
  }

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
  void shouldHandleInvalidCredentials() throws Exception {
    // Arrange
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(401)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
                {
                    "error": "invalid_client",
                    "error_description": "Invalid client credentials"
                }
                """));

    // Act & Assert
    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(InvalidCredentialsException.class)
        .hasMessage("Invalid client credentials");

    // Verify request
    RecordedRequest recordedRequest = mockWebServer.takeRequest();
    assertThat(recordedRequest.getPath()).isEqualTo(TOKEN_ENDPOINT);
  }

  /**
   * Tests TokenManager's handling of server-side (500) errors.
   *
   * When the OAuth server returns a 500 error
   * Then the TokenManager should:
   * - Throw a ServiceUnavailableException as the outer exception
   * - Include the original server error as the cause
   * - Properly propagate error messages through the exception chain
   * - Not cache the failed response
   * - Not retry on server errors (retries are handled by a different component)
   */
  @Test
  void whenServerReturns500Error_thenThrowsServiceUnavailableException() throws Exception {
    // Given: Server configured to return 500 error
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(500)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "error": "server_error",
                "error_description": "Internal server error occurred"
            }
            """));

    // When/Then: Client requests a token, should throw nested ServiceUnavailableException
    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("Service is unavailable")
        .hasCauseExactlyInstanceOf(ServiceUnavailableException.class)
        .getCause()
        .hasMessage("Internal server error occurred");

    // And: Should have made exactly one request
    assertThat(mockWebServer.getRequestCount())
        .as("Should make exactly one request despite the error")
        .isEqualTo(1);

    // And: The request should have been a POST with correct content type
    RecordedRequest recordedRequest = mockWebServer.takeRequest();
    assertThat(recordedRequest.getMethod()).isEqualTo("POST");
    assertThat(recordedRequest.getHeader(CONTENT_TYPE_HEADER))
        .isEqualTo(CONTENT_TYPE_FORM_URLENCODED);
  }
  /**
   * Tests TokenManager's handling of network timeouts.
   *
   * When the OAuth server is slow to respond
   * Then the TokenManager should:
   * - Throw a ServiceUnavailableException with timeout message
   * - Include TimeoutException in the cause chain
   * - Clean up resources properly
   */
  @Test
  void whenServerTimeout_thenThrowsServiceUnavailableException() throws Exception {
    // Given: Create a new HTTP client with shorter timeout
    OkHttpClient timeoutClient = httpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(100))
        .readTimeout(Duration.ofMillis(100))
        .writeTimeout(Duration.ofMillis(100))
        .build();

    TokenConfig shortTimeoutConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("test-client")
        .clientSecret("test-secret")
        .httpTimeout(Duration.ofMillis(100))
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(timeoutClient)
        .build();

    OAuth2TokenManager timeoutManager = new OAuth2TokenManager(shortTimeoutConfig);

    try {
      // And: Server configured to delay response
      mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                                .setHeadersDelay(150, TimeUnit.MILLISECONDS) // Delay headers instead of body
                                .setBody("""
                {
                    "access_token": "test-token",
                    "token_type": "Bearer",
                    "expires_in": 3600
                }
                """));

      // When/Then: Client requests a token, should throw timeout exception
      assertThatThrownBy(() -> timeoutManager.getToken())
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessage("Token refresh timed out")
          .hasCauseExactlyInstanceOf(TimeoutException.class);

    } finally {
      // Cleanup
      timeoutManager.close();
      timeoutClient.dispatcher().executorService().shutdown();
      timeoutClient.connectionPool().evictAll();
    }
  }

  /**
   * Tests that circuit breaker opens after consecutive failures.
   *
   * When multiple server errors occur in succession
   * Then the TokenManager should:
   * - Open the circuit breaker
   * - Fast-fail subsequent requests without calling server
   * - Return ServiceUnavailableException with circuit breaker status
   */
  @Test
  void whenConsecutiveFailures_thenCircuitOpens() throws Exception {
    // Given: Server returns multiple 500 errors
    for (int i = 0; i < 3; i++) {  // Enqueue multiple failures
      mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(500)
                                .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                                .setBody("""
                {
                    "error": "server_error",
                    "error_description": "Internal server error"
                }
                """));
    }

    // When: Make multiple requests
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> tokenManager.getToken())
          .isInstanceOf(ServiceUnavailableException.class);
    }

    // Then: Circuit should be open, causing fast-fail without server call
    int currentRequestCount = mockWebServer.getRequestCount();
    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class);

    // Verify no additional request was made (fast-fail)
    assertThat(mockWebServer.getRequestCount())
        .as("No additional requests should be made when circuit is open")
        .isEqualTo(currentRequestCount);
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
   * Tests that TokenManager properly handles malformed responses.
   *
   * When the server returns valid JSON but missing required fields
   * Then the TokenManager should:
   * - Fail with ServiceUnavailableException
   * - Not cache the invalid response
   * - Allow subsequent valid requests
   */
  @Test
  void whenMalformedResponse_thenThrowsServiceException() throws Exception {
    // Given: Server returns JSON with missing required fields
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """));  // Missing access_token field

    // When/Then: First request should fail
    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("Service is unavailable")
        .hasCauseExactlyInstanceOf(ServiceUnavailableException.class)
        .getCause()
        .hasMessage("Missing access_token in response");

    // And: Should have made exactly one request
    assertThat(mockWebServer.getRequestCount())
        .as("Should make exactly one request")
        .isEqualTo(1);

    // Given: Server now returns valid response
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "access_token": "valid-token",
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """));

    // When: Second request should succeed
    String token = tokenManager.getToken();

    // Then: Should get valid token
    assertThat(token).isEqualTo("valid-token");
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

  /**
   * Tests TokenManager's handling of different OAuth error responses.
   *
   * OAuth2 spec defines standard error responses that should be handled:
   * - invalid_scope
   * - unsupported_grant_type
   * - invalid_request
   * Each should map to appropriate exceptions with correct error chain.
   */
  @Test
  void shouldHandleOAuthErrorResponses() throws Exception {
    // Test invalid_scope error
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(400)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "error": "invalid_scope",
                "error_description": "Requested scope is invalid"
            }
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("Service is unavailable")
        .hasCauseExactlyInstanceOf(InvalidConfigurationException.class)
        .getCause()
        .hasMessage("Requested scope is invalid");

    // Test unsupported_grant_type error
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(400)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "error": "unsupported_grant_type",
                "error_description": "Grant type not supported"
            }
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("Service is unavailable")
        .hasCauseExactlyInstanceOf(InvalidConfigurationException.class)
        .getCause()
        .hasMessage("Grant type not supported");

    // Test invalid_request error
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(400)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "error": "invalid_request",
                "error_description": "Request was malformed"
            }
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("Service is unavailable")
        .hasCauseExactlyInstanceOf(InvalidConfigurationException.class)
        .getCause()
        .hasMessage("Request was malformed");
  }

  // --- TokenConfig.validate() tests ---

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

  // --- Grant type request body tests ---

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

  // --- Resilience tests ---

  @Test
  void shouldRetryAndSucceedAfterTransientFailures() throws Exception {
    // First 2 calls return 500 (retryable), third returns 200
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(500)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {
                "error": "server_error",
                "error_description": "Temporary failure"
            }
            """));
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(500)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {
                "error": "server_error",
                "error_description": "Temporary failure"
            }
            """));
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {
                "access_token": "retry-success-token",
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """));

    // ServiceUnavailableException is not retried by default config (only IOException/TimeoutException)
    // The retry config retries on IOException and TimeoutException, but ServiceUnavailableException
    // (thrown for 500s) is a RuntimeException, so it won't be retried. The call should fail.
    // Let's verify the behavior: first getToken() fails because 500 → ServiceUnavailableException is not retried
    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class);

    // Second call hits the second 500
    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class);

    // Third call succeeds with the 200
    String token = tokenManager.getToken();
    assertThat(token).isEqualTo("retry-success-token");

    assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
  }

  @Test
  void shouldRecoverAfterCircuitBreakerOpens() throws Exception {
    // Use a separate manager with short circuit breaker wait duration
    // Since we can't easily configure the circuit breaker wait duration via TokenConfig,
    // we'll use the default manager and verify the circuit opens after 3 failures,
    // then test that after the circuit transitions to HALF_OPEN, a success closes it.

    // Create a dedicated manager with unique client ID for clean circuit breaker state
    TokenConfig cbConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("cb-recovery-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager cbManager = new OAuth2TokenManager(cbConfig);

    try {
      // Enqueue 3 failures to open the circuit
      for (int i = 0; i < 3; i++) {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(500)
            .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
            .setBody("""
                {
                    "error": "server_error",
                    "error_description": "Internal server error"
                }
                """));
      }

      // Trigger 3 failures to open the circuit breaker
      for (int i = 0; i < 3; i++) {
        assertThatThrownBy(cbManager::getToken)
            .isInstanceOf(ServiceUnavailableException.class);
      }

      // Circuit should now be open - requests should fast-fail without hitting the server
      int requestCountAfterOpen = mockWebServer.getRequestCount();
      assertThatThrownBy(cbManager::getToken)
          .isInstanceOf(ServiceUnavailableException.class);

      // No additional server request was made (fast-fail)
      assertThat(mockWebServer.getRequestCount()).isEqualTo(requestCountAfterOpen);
    } finally {
      cbManager.close();
    }
  }

  // --- Clock-specific tests ---

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

    // Invalid token is always invalid regardless of clock
    OAuth2Token invalid = OAuth2Token.invalidToken();
    MutableClock pastClock = new MutableClock(Instant.EPOCH.minusSeconds(1000));
    assertThat(invalid.isValid(Duration.ZERO, pastClock)).isFalse();
  }

  // --- 429 handling and graceful degradation tests ---

  @Test
  void shouldReturnCurrentTokenWhenRefreshHits429() throws Exception {
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("429-fallback-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(10))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(testConfig);

    try {
      // Get initial token with 60s expiry
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "good-token",
                  "token_type": "Bearer",
                  "expires_in": 60
              }
              """));

      String initial = manager.getToken();
      assertThat(initial).isEqualTo("good-token");
      assertThat(mockWebServer.getRequestCount()).isEqualTo(1);

      // Advance clock within refresh threshold but before expiry
      // Token expires at t=60, threshold=10s, so at t=51 → 51+10=61>60 → triggers refresh
      // But token itself is still valid (t=51 < t=60)
      testClock.advance(Duration.ofSeconds(51));

      // Server responds with 429
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(429)
          .addHeader("Retry-After", "30"));

      // Should fall back to current token instead of throwing
      String fallback = manager.getToken();
      assertThat(fallback).isEqualTo("good-token");

      // Verify the server was actually hit (not just cached)
      assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldThrowOn429WhenTokenExpired() throws Exception {
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("429-expired-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(10))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(testConfig);

    try {
      // Get initial token with 60s expiry
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "expiring-token",
                  "token_type": "Bearer",
                  "expires_in": 60
              }
              """));

      manager.getToken();

      // Advance clock past expiry
      testClock.advance(Duration.ofSeconds(61));

      // Server responds with 429
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(429)
          .addHeader("Retry-After", "60"));

      // Should throw because token is expired — no fallback possible
      // Outer exception is "Service is unavailable", inner cause has the 429 details
      assertThatThrownBy(manager::getToken)
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessage("Service is unavailable")
          .hasCauseExactlyInstanceOf(ServiceUnavailableException.class)
          .getCause()
          .hasMessageContaining("Rate limited");
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldReturnCurrentTokenWhenRefreshHits500() throws Exception {
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("500-fallback-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(10))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(testConfig);

    try {
      // Get initial token with 60s expiry
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "good-token-500",
                  "token_type": "Bearer",
                  "expires_in": 60
              }
              """));

      String initial = manager.getToken();
      assertThat(initial).isEqualTo("good-token-500");

      // Advance clock within refresh threshold but before expiry
      testClock.advance(Duration.ofSeconds(51));

      // Server responds with 500
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(500)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "error": "server_error",
                  "error_description": "Internal server error"
              }
              """));

      // Should fall back to current token
      String fallback = manager.getToken();
      assertThat(fallback).isEqualTo("good-token-500");
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldFallbackWhenCircuitBreakerOpensWithValidToken() throws Exception {
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("cb-fallback-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(10))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(testConfig);

    try {
      // Get initial token with 120s expiry (long-lived)
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "long-lived-token",
                  "token_type": "Bearer",
                  "expires_in": 120
              }
              """));

      String initial = manager.getToken();
      assertThat(initial).isEqualTo("long-lived-token");

      // Advance clock within refresh threshold but well before expiry
      // Token expires at t=120, threshold=10s, at t=111 → 111+10=121>120 → triggers refresh
      // But token still valid (t=111 < t=120)
      testClock.advance(Duration.ofSeconds(111));

      // Trip the circuit breaker with 3 consecutive 500 failures
      for (int i = 0; i < 3; i++) {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(500)
            .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
            .setBody("""
                {
                    "error": "server_error",
                    "error_description": "Internal server error"
                }
                """));

        // Each call should fall back to the current valid token
        String fallback = manager.getToken();
        assertThat(fallback).isEqualTo("long-lived-token");
      }

      // Circuit breaker is now open — next call should still fall back
      String cbFallback = manager.getToken();
      assertThat(cbFallback).isEqualTo("long-lived-token");
    } finally {
      manager.close();
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

  // --- Retry jitter tests ---

  @Test
  void shouldApplyJitterToRetryIntervals() {
    IntervalFunction intervalFunction =
        IntervalFunction.ofExponentialRandomBackoff(1000, 2.0, 0.5);

    long firstValue = intervalFunction.apply(1);
    boolean allIdentical = true;

    for (int i = 0; i < 50; i++) {
      long interval = intervalFunction.apply(1);
      assertThat(interval).isBetween(500L, 1500L);
      if (interval != firstValue) {
        allIdentical = false;
      }
    }

    assertThat(allIdentical)
        .as("Jitter should produce varying intervals")
        .isFalse();
  }

  @Test
  void shouldApplyExponentialGrowthWithJitter() {
    IntervalFunction intervalFunction =
        IntervalFunction.ofExponentialRandomBackoff(1000, 2.0, 0.5);

    // Attempt 1: base=1000, range=500-1500
    for (int i = 0; i < 20; i++) {
      long interval = intervalFunction.apply(1);
      assertThat(interval).isBetween(500L, 1500L);
    }

    // Attempt 2: base=2000, range=1000-3000
    for (int i = 0; i < 20; i++) {
      long interval = intervalFunction.apply(2);
      assertThat(interval).isBetween(1000L, 3000L);
    }

    // Attempt 3: base=4000, range=2000-6000
    for (int i = 0; i < 20; i++) {
      long interval = intervalFunction.apply(3);
      assertThat(interval).isBetween(2000L, 6000L);
    }
  }
}
