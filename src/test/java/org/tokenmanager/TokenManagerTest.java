package org.tokenmanager;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
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
  private Oauth2TokenManager tokenManager;



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
        .hostnameVerifier((hostname, session) -> true)
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
    tokenManager = new Oauth2TokenManager(tokenConfig);

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

    Oauth2TokenManager timeoutManager = new Oauth2TokenManager(shortTimeoutConfig);

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
    // Create a fresh TokenManager for this test to avoid circuit breaker state
    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("refresh-test-client")  // Different client ID to avoid shared state
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofMillis(500))  // Short refresh threshold for testing
        .httpClient(httpClient)
        .build();

    Oauth2TokenManager refreshManager = new Oauth2TokenManager(testConfig);

    try {
      // Given: Initial token with short expiry
      mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                                .setBody("""
                {
                    "access_token": "initial-token",
                    "token_type": "Bearer",
                    "expires_in": 2
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

      // Wait for token to approach expiry
      Thread.sleep(1500);  // Wait long enough for initial token to be near expiry

      // When: Request token again
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

    Oauth2TokenManager passwordManager = new Oauth2TokenManager(passwordConfig);

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

      Oauth2TokenManager clientManager = new Oauth2TokenManager(clientConfig);

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
}
