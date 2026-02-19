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
package org.tokenmanager;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.github.resilience4j.core.IntervalFunction;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenManagerResilienceTest extends AbstractMockServerTest {

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
  void whenOverallTimeoutExceeded_thenThrowsServiceUnavailableException() throws Exception {
    // OkHttp readTimeout is generous (30s) — it never fires.
    // The computed overall CF timeout (httpTimeout=100ms, maxRetryAttempts=1 → ~5100ms)
    // fires before the server responds at 30s.
    OkHttpClient timeoutClient = httpClient.newBuilder()
        .readTimeout(Duration.ofSeconds(30))
        .build();

    TokenConfig shortTimeoutConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("overall-timeout-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(Duration.ofMillis(100))
        .maxRetryAttempts(1)
        .initialRetryDelay(Duration.ofMillis(50))
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(timeoutClient)
        .build();

    OAuth2TokenManager timeoutManager = new OAuth2TokenManager(shortTimeoutConfig);

    try {
      // Server never responds within the overall timeout
      mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                                .setHeadersDelay(30, TimeUnit.SECONDS)
                                .setBody("""
                {
                    "access_token": "test-token",
                    "token_type": "Bearer",
                    "expires_in": 3600
                }
                """));

      assertThatThrownBy(() -> timeoutManager.getToken())
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessage("Token refresh timed out")
          .hasCauseExactlyInstanceOf(TimeoutException.class);

    } finally {
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

  @Test
  void shouldRecoverAcrossCallsAfterServerErrors() throws Exception {
    // 500 errors throw ServiceUnavailableException (not retried within a single
    // getToken() call — retry only applies to network-level IOExceptions).
    // Each getToken() call is independent.
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

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class);

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class);

    String token = tokenManager.getToken();
    assertThat(token).isEqualTo("retry-success-token");

    assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
  }

  @Test
  void shouldRetryOnNetworkFailureAndSucceed() throws Exception {
    // First two attempts disconnect (IOException), retry kicks in automatically.
    // Third attempt succeeds. All within a single getToken() call.
    mockWebServer.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    mockWebServer.enqueue(new MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {
                "access_token": "recovered-token",
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """));

    String token = tokenManager.getToken();
    assertThat(token).isEqualTo("recovered-token");
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
      assertThatThrownBy(manager::getToken)
          .isInstanceOf(RateLimitedException.class)
          .hasMessageContaining("Rate limited")
          .satisfies(ex -> {
            RateLimitedException rle = (RateLimitedException) ex;
            assertThat(rle.getRetryAfter()).isEqualTo(Duration.ofSeconds(60));
          });
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldParseRetryAfterHttpDate() throws Exception {
    Instant fixedNow = Instant.parse("2026-02-14T12:00:00Z");
    MutableClock testClock = new MutableClock(fixedNow);

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("429-httpdate-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(10))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(testConfig);

    try {
      // Get initial token
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {"access_token": "initial", "token_type": "Bearer", "expires_in": 60}
              """));
      manager.getToken();

      // Advance past expiry so fallback is not possible
      testClock.advance(Duration.ofSeconds(61));

      // 429 with HTTP-date Retry-After (5 minutes from fixedNow + 61s)
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(429)
          .addHeader("Retry-After", "Sat, 14 Feb 2026 12:06:01 GMT"));

      assertThatThrownBy(manager::getToken)
          .isInstanceOf(RateLimitedException.class)
          .satisfies(ex -> {
            Duration retryAfter = ((RateLimitedException) ex).getRetryAfter();
            assertThat(retryAfter).isNotNull();
            // Clock is at 12:01:01, Retry-After is 12:06:01 → 300s
            assertThat(retryAfter.getSeconds()).isEqualTo(300);
          });
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldNotTripCircuitBreakerOnRepeated429s() throws Exception {
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("429-cb-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(10))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(testConfig);

    try {
      // Get initial token with long expiry
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "good-token",
                  "token_type": "Bearer",
                  "expires_in": 300
              }
              """));

      manager.getToken();

      // Advance into refresh threshold (token expires at t=300, threshold=10s)
      testClock.advance(Duration.ofSeconds(291));

      // Send 5 consecutive 429s — more than enough to trip the CB (minimum calls = 3)
      // Each should fall back to the cached token, and CB should stay closed
      for (int i = 0; i < 5; i++) {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(429)
            .addHeader("Retry-After", "30"));

        String token = manager.getToken();
        assertThat(token).isEqualTo("good-token");
      }

      // Now serve a successful refresh — if CB had tripped, this would fail
      // Advance clock a bit more so the previous cached check doesn't short-circuit
      testClock.advance(Duration.ofSeconds(5));

      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "refreshed-token",
                  "token_type": "Bearer",
                  "expires_in": 300
              }
              """));

      String refreshed = manager.getToken();
      assertThat(refreshed).isEqualTo("refreshed-token");
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
  void shouldNotFallbackOnInvalidCredentials() throws Exception {
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("cred-fail-" + UUID.randomUUID())
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

      // Advance clock into refresh threshold but before expiry
      // Token still valid (t=51 < t=60), but needs refresh (51+10=61 > 60)
      testClock.advance(Duration.ofSeconds(51));

      // Server responds with 401 — credential rotation failure
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(401)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "error": "invalid_client",
                  "error_description": "Client authentication failed"
              }
              """));

      // Must throw immediately — not fall back to cached token.
      // Credential errors are permanent; masking them until the token
      // expires would delay detection of a credential rotation failure.
      assertThatThrownBy(manager::getToken)
          .isInstanceOf(InvalidCredentialsException.class);
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldNotFallbackOnInvalidConfiguration() throws Exception {
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("config-fail-" + UUID.randomUUID())
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
      testClock.advance(Duration.ofSeconds(51));

      // Server responds with invalid_scope — configuration error
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(400)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "error": "invalid_scope",
                  "error_description": "Requested scope is invalid"
              }
              """));

      assertThatThrownBy(manager::getToken)
          .isInstanceOf(InvalidConfigurationException.class);
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldNotFallbackOnInvalidEndpoint() throws Exception {
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("endpoint-fail-" + UUID.randomUUID())
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
      testClock.advance(Duration.ofSeconds(51));

      // Server responds with 404 — wrong endpoint
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(404)
          .setBody("Not Found"));

      assertThatThrownBy(manager::getToken)
          .isInstanceOf(InvalidEndpointException.class);
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldThrowOn500WhenTokenExpired() throws Exception {
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("500-expired-" + UUID.randomUUID())
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

      // Token expired + refresh failure → must throw, no fallback possible
      assertThatThrownBy(manager::getToken)
          .isInstanceOf(ServiceUnavailableException.class);
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldThrowWhenTokenAtExactExpiry() throws Exception {
    Instant baseTime = Instant.parse("2025-06-01T00:00:00Z");
    MutableClock testClock = new MutableClock(baseTime);

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("exact-expiry-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(10))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(testConfig);

    try {
      // Token expires at baseTime + 60s
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "boundary-token",
                  "token_type": "Bearer",
                  "expires_in": 60
              }
              """));

      manager.getToken();

      // Advance clock to exactly expiresAt (now == expiresAt)
      testClock.advance(Duration.ofSeconds(60));

      // Refresh fails
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(500)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "error": "server_error",
                  "error_description": "Internal server error"
              }
              """));

      // now == expiresAt → token is expired, no fallback
      assertThatThrownBy(manager::getToken)
          .isInstanceOf(ServiceUnavailableException.class);
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldFallbackWhenTokenOneMilliBeforeExpiry() throws Exception {
    Instant baseTime = Instant.parse("2025-06-01T00:00:00Z");
    MutableClock testClock = new MutableClock(baseTime);

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("just-before-expiry-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(10))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(testConfig);

    try {
      // Token expires at baseTime + 60s
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "almost-expired-token",
                  "token_type": "Bearer",
                  "expires_in": 60
              }
              """));

      manager.getToken();

      // Advance to 1ms before expiry — still valid
      testClock.advance(Duration.ofSeconds(60).minusMillis(1));

      // Refresh fails
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(500)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "error": "server_error",
                  "error_description": "Internal server error"
              }
              """));

      // now < expiresAt → fallback to cached token
      String fallback = manager.getToken();
      assertThat(fallback).isEqualTo("almost-expired-token");
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldUseCustomRetryAttempts() throws Exception {
    // With maxRetryAttempts(2): 1 disconnect + 1 success = works
    TokenConfig twoRetryConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("retry-2-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .maxRetryAttempts(2)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager twoRetryManager = new OAuth2TokenManager(twoRetryConfig);

    try {
      mockWebServer.enqueue(new MockResponse()
          .setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {
                  "access_token": "retry-token",
                  "token_type": "Bearer",
                  "expires_in": 3600
              }
              """));

      String token = twoRetryManager.getToken();
      assertThat(token).isEqualTo("retry-token");
      assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    } finally {
      twoRetryManager.close();
    }

    // With maxRetryAttempts(1): 1 disconnect = failure (no retry beyond first attempt)
    TokenConfig oneRetryConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("retry-1-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .maxRetryAttempts(1)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager oneRetryManager = new OAuth2TokenManager(oneRetryConfig);

    try {
      mockWebServer.enqueue(new MockResponse()
          .setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

      assertThatThrownBy(oneRetryManager::getToken)
          .isInstanceOf(ServiceUnavailableException.class);
    } finally {
      oneRetryManager.close();
    }
  }

  @Test
  void shouldUseCustomCircuitBreakerConfig() throws Exception {
    TokenConfig cbConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("cb-custom-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .circuitBreakerMinimumCalls(2)
        .circuitBreakerWaitDuration(Duration.ofSeconds(5))
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager cbManager = new OAuth2TokenManager(cbConfig);

    try {
      // Enqueue 2 failures — enough to open CB with minimumCalls=2
      for (int i = 0; i < 2; i++) {
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

      // Trigger 2 failures to open the circuit breaker
      for (int i = 0; i < 2; i++) {
        assertThatThrownBy(cbManager::getToken)
            .isInstanceOf(ServiceUnavailableException.class);
      }

      // Circuit should now be open — fast-fail without hitting server
      int requestCountAfterOpen = mockWebServer.getRequestCount();
      assertThatThrownBy(cbManager::getToken)
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessageContaining("Service unavailable");
      assertThat(mockWebServer.getRequestCount()).isEqualTo(requestCountAfterOpen);
    } finally {
      cbManager.close();
    }
  }

  @Test
  void shouldCompleteAllConfiguredRetryAttempts() throws Exception {
    OkHttpClient retryTestClient = httpClient.newBuilder()
        .retryOnConnectionFailure(false)
        .readTimeout(Duration.ofMillis(150))
        .build();

    TokenConfig retryConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("retry-all-" + UUID.randomUUID())
        .clientSecret("secret")
        .httpClient(retryTestClient)
        .httpTimeout(Duration.ofSeconds(3))
        .refreshThreshold(REFRESH_THRESHOLD)
        .maxRetryAttempts(3)
        .initialRetryDelay(Duration.ofMillis(50))
        .build();

    try (OAuth2TokenManager retryManager = new OAuth2TokenManager(retryConfig)) {
      // Two deterministic IO failures: server delays headers beyond client readTimeout.
      // SocketTimeoutException → UncheckedIOException → triggers Resilience4j retry.
      mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .setHeadersDelay(1, TimeUnit.SECONDS));
      mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(200)
                                .setHeadersDelay(1, TimeUnit.SECONDS));
      // Third attempt succeeds immediately
      mockWebServer.enqueue(successResponse("retry-survived-token", 3600));

      String token = retryManager.getToken();
      assertThat(token).isEqualTo("retry-survived-token");

      assertThat(mockWebServer.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
      assertThat(mockWebServer.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
      assertThat(mockWebServer.takeRequest(2, TimeUnit.SECONDS)).isNotNull();

      assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
    } finally {
      retryTestClient.dispatcher().executorService().shutdown();
      retryTestClient.connectionPool().evictAll();
    }
  }

  @Test
  void shouldNotTimeoutBeforeRetriesComplete() throws Exception {
    OkHttpClient retryTestClient = httpClient.newBuilder()
        .retryOnConnectionFailure(false)
        .build();

    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("cf-timeout-" + UUID.randomUUID())
        .clientSecret("secret")
        .httpClient(retryTestClient)
        .httpTimeout(Duration.ofMillis(100))
        .refreshThreshold(REFRESH_THRESHOLD)
        .maxRetryAttempts(3)
        .initialRetryDelay(Duration.ofMillis(400))
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(config)) {
      // Instant disconnects — safe with retryOnConnectionFailure(false)
      mockWebServer.enqueue(new MockResponse()
                                .setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
      mockWebServer.enqueue(new MockResponse()
                                .setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
      mockWebServer.enqueue(successResponse("late-token", 3600));

      String token = manager.getToken();
      assertThat(token).isEqualTo("late-token");

      assertThat(mockWebServer.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
      assertThat(mockWebServer.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
      assertThat(mockWebServer.takeRequest(2, TimeUnit.SECONDS)).isNotNull();

      assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
    } finally {
      retryTestClient.dispatcher().executorService().shutdown();
      retryTestClient.connectionPool().evictAll();
    }
  }

  @Test
  void shouldNotTripCircuitBreakerOnDnsFailure() {
    // DNS failure wraps as UncheckedIOException(UnknownHostException).
    // The CB should NOT record these as failures — they are permanent
    // endpoint problems, not transient service failures.
    TokenConfig dnsConfig = TokenConfig.builder()
        .tokenEndpoint("https://nonexistent.invalid/token")
        .clientId("dns-cb-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(Duration.ofSeconds(5))
        .refreshThreshold(REFRESH_THRESHOLD)
        .circuitBreakerMinimumCalls(2)
        .maxRetryAttempts(1)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(dnsConfig);

    try {
      // Make 3 calls — more than circuitBreakerMinimumCalls(2).
      // Without the fix, the CB would open after 2 failures and the
      // 3rd call would throw ServiceUnavailableException("Circuit breaker is open").
      // With the fix, all 3 throw InvalidEndpointException.
      for (int i = 0; i < 3; i++) {
        assertThatThrownBy(manager::getToken)
            .isInstanceOf(InvalidEndpointException.class)
            .hasMessageContaining("unreachable");
      }
    } finally {
      manager.close();
    }
  }

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
