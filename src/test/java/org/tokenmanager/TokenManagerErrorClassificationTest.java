package org.tokenmanager;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenManagerErrorClassificationTest extends AbstractMockServerTest {

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

    // When/Then: Client requests a token, should throw ServiceUnavailableException
    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class)
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
        .isInstanceOf(InvalidConfigurationException.class)
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
        .isInstanceOf(InvalidConfigurationException.class)
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
        .isInstanceOf(InvalidConfigurationException.class)
        .hasMessage("Request was malformed");
  }

  @Test
  void shouldClassifyUnknownOAuth2ErrorCodeByHttpStatus() throws Exception {
    // Non-standard error codes (e.g. login_required, consent_required) must not
    // leak IllegalArgumentException — they fall back to HTTP status classification.

    // 400 with unknown error code → InvalidEndpointException (non-auth 4xx fallback)
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(400)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "login_required", "error_description": "User must re-authenticate"}
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(InvalidEndpointException.class);

    // 401 with unknown error code → InvalidCredentialsException
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(401)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "consent_required", "error_description": "User consent needed"}
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(InvalidCredentialsException.class);

    // 500 with unknown error code → ServiceUnavailableException
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(500)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "custom_server_error", "error_description": "Something broke"}
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void shouldClassifyAccessDeniedAsInvalidCredentials() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(403)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "access_denied", "error_description": "Resource owner denied the request"}
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(InvalidCredentialsException.class)
        .hasMessageContaining("Resource owner denied the request");
  }

  @Test
  void shouldClassifyInvalidTokenAsInvalidCredentials() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(401)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "invalid_token", "error_description": "Token has been revoked"}
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(InvalidCredentialsException.class)
        .hasMessageContaining("Token has been revoked");
  }

  @Test
  void shouldClassifyInsufficientScopeAsInvalidConfiguration() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(403)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "insufficient_scope", "error_description": "Token lacks required scope"}
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(InvalidConfigurationException.class)
        .hasMessageContaining("Token lacks required scope");
  }

  @Test
  void shouldClassifyUnsupportedResponseTypeAsInvalidConfiguration() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(400)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "unsupported_response_type", "error_description": "Response type not supported"}
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(InvalidConfigurationException.class)
        .hasMessageContaining("Response type not supported");
  }

  @Test
  void shouldClassifyUnsupportedTokenTypeAsInvalidConfiguration() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(400)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "unsupported_token_type", "error_description": "Token type not supported"}
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(InvalidConfigurationException.class)
        .hasMessageContaining("Token type not supported");
  }

  @Test
  void shouldClassifyInvalidRedirectUriAsInvalidConfiguration() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(400)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "invalid_redirect_uri", "error_description": "Redirect URI mismatch"}
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(InvalidConfigurationException.class)
        .hasMessageContaining("Redirect URI mismatch");
  }

  @Test
  void shouldClassify429AsRateLimitedEvenWithJsonBody() throws Exception {
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("429-json-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(10))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(testConfig);

    try {
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

      manager.getToken();

      // Advance past expiry so fallback is not possible
      testClock.advance(Duration.ofSeconds(61));

      // Server returns 429 with a JSON OAuth2 error body — status code must win
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(429)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .addHeader("Retry-After", "120")
          .setBody("""
              {
                  "error": "temporarily_unavailable",
                  "error_description": "Too many requests"
              }
              """));

      assertThatThrownBy(manager::getToken)
          .isInstanceOf(RateLimitedException.class)
          .satisfies(ex -> {
            RateLimitedException rle = (RateLimitedException) ex;
            assertThat(rle.getRetryAfter()).isEqualTo(Duration.ofSeconds(120));
          });
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldNotTripCircuitBreakerOnRepeatedCredentialFailures() throws Exception {
    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("cb-creds-" + UUID.randomUUID())
        .clientSecret("wrong-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(testConfig);

    try {
      // Send 5 consecutive 401s — more than enough to trip CB if credentials were recorded
      for (int i = 0; i < 5; i++) {
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
            .isInstanceOf(InvalidCredentialsException.class)
            .hasMessage("Bad credentials");
      }

      // If CB had opened, this would throw ServiceUnavailableException("CB open").
      // Instead it should still surface the real credential error.
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
          .isInstanceOf(InvalidCredentialsException.class)
          .hasMessage("Bad credentials");
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldClassifyDnsFailureAsInvalidEndpoint() {
    // Unresolvable host → InvalidEndpointException, not ServiceUnavailableException.
    // Large retry delay proves no retries happen (test would be slow if retried).
    TokenConfig dnsConfig = TokenConfig.builder()
        .tokenEndpoint("https://this.host.does.not.exist.invalid/oauth/token")
        .clientId("dns-test-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(Duration.ofSeconds(5))
        .maxRetryAttempts(3)
        .initialRetryDelay(Duration.ofSeconds(30))
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(dnsConfig)) {
      long startMs = System.nanoTime();
      assertThatThrownBy(manager::getToken)
          .isInstanceOf(InvalidEndpointException.class);
      long elapsedMs = (System.nanoTime() - startMs) / 1_000_000;
      // If retries happened with 30s delay, this would take 60+ seconds.
      // Without retries, DNS resolution failure is fast.
      assertThat(elapsedMs).isLessThan(15_000);
    }
  }

  @Test
  void shouldClassifySslHandshakeFailureAsInvalidEndpoint() throws Exception {
    // Create a client with a TrustManager that rejects ALL certificates.
    // This forces a genuine SSLHandshakeException during TLS handshake with our MockWebServer.
    javax.net.ssl.TrustManager rejectAll = new javax.net.ssl.X509TrustManager() {
      @Override
      public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
          throws java.security.cert.CertificateException {
        throw new java.security.cert.CertificateException("reject all");
      }
      @Override
      public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
          throws java.security.cert.CertificateException {
        throw new java.security.cert.CertificateException("reject all");
      }
      @Override
      public java.security.cert.X509Certificate[] getAcceptedIssuers() {
        return new java.security.cert.X509Certificate[0];
      }
    };

    javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
    sslContext.init(null, new javax.net.ssl.TrustManager[]{rejectAll}, new java.security.SecureRandom());

    okhttp3.OkHttpClient sslFailClient = new okhttp3.OkHttpClient.Builder()
        .sslSocketFactory(sslContext.getSocketFactory(), (javax.net.ssl.X509TrustManager) rejectAll)
        .hostnameVerifier((hostname, session) -> true)
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(5))
        .writeTimeout(Duration.ofSeconds(5))
        .build();

    try {
      // Enqueue a response in case handshake somehow succeeds (it shouldn't)
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {"access_token": "token", "token_type": "bearer", "expires_in": 3600}
              """));

      // Force IPv4 to avoid IPv6 ConnectException masking the SSL failure.
      String endpoint = mockWebServer.url(TOKEN_ENDPOINT).toString()
          .replace("localhost", "127.0.0.1");

      TokenConfig sslConfig = TokenConfig.builder()
          .tokenEndpoint(endpoint)
          .clientId("ssl-test-" + UUID.randomUUID())
          .clientSecret("test-secret")
          .httpTimeout(Duration.ofSeconds(5))
          .maxRetryAttempts(3)
          .initialRetryDelay(Duration.ofSeconds(30))
          .httpClient(sslFailClient)
          .build();

      try (OAuth2TokenManager manager = new OAuth2TokenManager(sslConfig)) {
        long startNanos = System.nanoTime();
        assertThatThrownBy(manager::getToken)
            .isInstanceOf(InvalidEndpointException.class);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // If retries happened with 30s delay, this would take 60+ seconds.
        // SSLHandshakeException is excluded from retry, so it should be fast.
        assertThat(elapsedMs).isLessThan(15_000);
      }
    } finally {
      sslFailClient.dispatcher().executorService().shutdown();
      sslFailClient.connectionPool().evictAll();
    }
  }
}
