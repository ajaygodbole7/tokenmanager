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

import java.time.Duration;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the code-review findings fixed in this change set.
 * Each test is annotated with the finding it guards.
 */
class TokenManagerReviewFixesTest extends AbstractMockServerTest {

  private MockResponse serverError() {
    return new MockResponse()
        .setResponseCode(500)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "server_error", "error_description": "boom"}
            """);
  }

  private MockResponse json(int code, String body) {
    return new MockResponse()
        .setResponseCode(code)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody(body);
  }

  // Finding #1 — the breaker recovers once its open window elapses (previously it
  // was short-circuited before ever attempting a permission and stayed open forever).
  @Test
  void circuitBreakerRecoversAfterWaitDurationElapses() throws Exception {
    OAuth2TokenManager mgr = new OAuth2TokenManager(TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("cb-recover-" + UUID.randomUUID())
        .clientSecret("secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .circuitBreakerMinimumCalls(2)
        .circuitBreakerWaitDuration(Duration.ofMillis(500))
        .httpClient(httpClient)
        .build());
    try {
      mockWebServer.enqueue(serverError());
      mockWebServer.enqueue(serverError());
      for (int i = 0; i < 2; i++) {
        assertThatThrownBy(mgr::getToken).isInstanceOf(ServiceUnavailableException.class);
      }

      int requestsWhenOpen = mockWebServer.getRequestCount();
      assertThatThrownBy(mgr::getToken)
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessageContaining("Circuit breaker is open");
      assertThat(mockWebServer.getRequestCount()).isEqualTo(requestsWhenOpen);

      // Wait out the open window; the next call must probe the server and recover.
      Thread.sleep(700);

      mockWebServer.enqueue(successResponse("recovered", 3600));
      assertThat(mgr.getToken()).isEqualTo("recovered");
    } finally {
      mgr.close();
    }
  }

  // Finding #2 — the breaker opens on consecutive failures even after a prior success
  // (with the old default 100-call window, one success kept it closed indefinitely).
  @Test
  void circuitBreakerOpensAfterPriorSuccessThenConsecutiveFailures() throws Exception {
    OAuth2TokenManager mgr = new OAuth2TokenManager(TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("cb-midlife-" + UUID.randomUUID())
        .clientSecret("secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .circuitBreakerMinimumCalls(3)
        .httpClient(httpClient)
        .build());
    try {
      mockWebServer.enqueue(successResponse("good", 3600));
      assertThat(mgr.getToken()).isEqualTo("good");
      mgr.invalidate();

      for (int i = 0; i < 3; i++) {
        mockWebServer.enqueue(serverError());
      }
      for (int i = 0; i < 3; i++) {
        assertThatThrownBy(mgr::getToken).isInstanceOf(ServiceUnavailableException.class);
      }

      int requestsWhenOpen = mockWebServer.getRequestCount();
      assertThatThrownBy(mgr::getToken)
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessageContaining("Circuit breaker is open");
      assertThat(mockWebServer.getRequestCount()).isEqualTo(requestsWhenOpen);
    } finally {
      mgr.close();
    }
  }

  // Finding #3 — a JSON null access_token is rejected, not served as the string "null".
  @Test
  void rejectsNullAccessToken() {
    mockWebServer.enqueue(json(200, """
        {"access_token": null, "token_type": "Bearer", "expires_in": 3600}
        """));
    assertThatThrownBy(tokenManager::getToken)
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("access_token");
  }

  // Finding #5 — a repeated scope token no longer crashes parsing of a valid response.
  @Test
  void parsesTokenWithDuplicateScopes() {
    mockWebServer.enqueue(json(200, """
        {"access_token": "t", "token_type": "Bearer", "expires_in": 3600, "scope": " read read write"}
        """));
    assertThat(tokenManager.getToken()).isEqualTo("t");
  }

  // Finding #14 — expires_in delivered as a numeric string is accepted.
  @Test
  void acceptsStringExpiresIn() {
    mockWebServer.enqueue(json(200, """
        {"access_token": "t", "token_type": "Bearer", "expires_in": "3600"}
        """));
    assertThat(tokenManager.getToken()).isEqualTo("t");
  }

  // Finding #11 — a huge Retry-After is clamped instead of overflowing, and the 429 is
  // still classified as rate limiting (previously it surfaced as ServiceUnavailable).
  @Test
  void clampsHugeRetryAfterAndStillRateLimits() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(429)
        .addHeader("Retry-After", "99999999999999999")
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("{\"error\": \"rate_limited\"}"));
    assertThatThrownBy(tokenManager::getToken)
        .isInstanceOf(RateLimitedException.class)
        .satisfies(ex -> assertThat(((RateLimitedException) ex).getRetryAfter())
            .isEqualTo(Duration.ofHours(24)));
  }

  // Finding #15 — a host-less HTTPS URL is rejected at validation, not per request.
  @Test
  void rejectsHostlessEndpoint() {
    assertThatThrownBy(() -> TokenConfig.builder()
        .tokenEndpoint("https://")
        .clientId("client")
        .clientSecret("secret")
        .build()
        .validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("valid HTTPS URL");
  }

  // Finding #13 — invalidate() clears an active 429 cooldown so the next call can fetch.
  @Test
  void invalidateClearsRateLimitCooldown() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(429)
        .addHeader("Retry-After", "3600")
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("{\"error\": \"rate_limited\"}"));
    assertThatThrownBy(tokenManager::getToken).isInstanceOf(RateLimitedException.class);

    tokenManager.invalidate();

    mockWebServer.enqueue(successResponse("fresh", 3600));
    assertThat(tokenManager.getToken()).isEqualTo("fresh");
  }
}
