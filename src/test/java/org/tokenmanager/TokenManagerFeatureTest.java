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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenManagerFeatureTest extends AbstractMockServerTest {

  @Test
  void shouldCallClientSecretSupplierOnEachRefresh() throws Exception {
    // Counter supplier returns "secret-0", "secret-1", etc.
    java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig supplierConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("supplier-test-" + UUID.randomUUID())
        .clientSecretSupplier(() -> "secret-" + counter.getAndIncrement())
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(1))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(supplierConfig)) {
      // First token fetch
      mockWebServer.enqueue(successResponse("token-1", 5));
      manager.getToken();

      RecordedRequest req1 = mockWebServer.takeRequest();
      assertThat(req1.getBody().readUtf8()).contains("client_secret=secret-0");

      // Advance clock past refresh threshold to force a second fetch
      testClock.advance(Duration.ofSeconds(5));
      mockWebServer.enqueue(successResponse("token-2", 3600));
      manager.getToken();

      RecordedRequest req2 = mockWebServer.takeRequest();
      assertThat(req2.getBody().readUtf8()).contains("client_secret=secret-1");
    }
  }

  @Test
  void shouldCallAssertionSupplierOnEachRefresh() throws Exception {
    // Counter supplier returns "assertion-0", "assertion-1", etc.
    java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig supplierConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("assertion-supplier-" + UUID.randomUUID())
        .clientSecretSupplier(() -> "secret")
        .grantType(OAuth2GrantType.JWT_BEARER)
        .assertionSupplier(() -> "assertion-" + counter.getAndIncrement())
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(1))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(supplierConfig)) {
      mockWebServer.enqueue(successResponse("jwt-token-1", 5));
      manager.getToken();

      RecordedRequest req1 = mockWebServer.takeRequest();
      assertThat(req1.getBody().readUtf8()).contains("assertion=assertion-0");

      testClock.advance(Duration.ofSeconds(5));
      mockWebServer.enqueue(successResponse("jwt-token-2", 3600));
      manager.getToken();

      RecordedRequest req2 = mockWebServer.takeRequest();
      assertThat(req2.getBody().readUtf8()).contains("assertion=assertion-1");
    }
  }

  @Test
  void shouldCallClientSecretSupplierForBasicAuth() throws Exception {
    // Counter supplier returns fresh secrets for BASIC auth header
    java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig basicConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("basic-supplier-" + UUID.randomUUID())
        .clientSecretSupplier(() -> "secret-" + counter.getAndIncrement())
        .clientAuthMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(1))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(basicConfig)) {
      mockWebServer.enqueue(successResponse("basic-token-1", 5));
      manager.getToken();

      RecordedRequest req1 = mockWebServer.takeRequest();
      String auth1 = req1.getHeader("Authorization");
      String decoded1 = new String(
          java.util.Base64.getDecoder().decode(auth1.substring(6)),
          java.nio.charset.StandardCharsets.UTF_8);
      assertThat(decoded1).endsWith(":secret-0");

      testClock.advance(Duration.ofSeconds(5));
      mockWebServer.enqueue(successResponse("basic-token-2", 3600));
      manager.getToken();

      RecordedRequest req2 = mockWebServer.takeRequest();
      String auth2 = req2.getHeader("Authorization");
      String decoded2 = new String(
          java.util.Base64.getDecoder().decode(auth2.substring(6)),
          java.nio.charset.StandardCharsets.UTF_8);
      assertThat(decoded2).endsWith(":secret-1");
    }
  }

  @Test
  void shouldWorkWithStaticClientSecret() throws Exception {
    // Backward compat: clientSecret(String) still works
    TokenConfig staticConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("static-secret-" + UUID.randomUUID())
        .clientSecret("static-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(staticConfig)) {
      mockWebServer.enqueue(successResponse("static-token", 3600));
      String token = manager.getToken();
      assertThat(token).isEqualTo("static-token");

      RecordedRequest req = mockWebServer.takeRequest();
      assertThat(req.getBody().readUtf8()).contains("client_secret=static-secret");
    }
  }

  @Test
  void shouldWorkWithStaticAssertion() throws Exception {
    // Backward compat: assertion(String) still works
    TokenConfig staticConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("static-assertion-" + UUID.randomUUID())
        .clientSecret("secret")
        .grantType(OAuth2GrantType.JWT_BEARER)
        .assertion("static-assertion-value")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(staticConfig)) {
      mockWebServer.enqueue(successResponse("jwt-static-token", 3600));
      String token = manager.getToken();
      assertThat(token).isEqualTo("jwt-static-token");

      RecordedRequest req = mockWebServer.takeRequest();
      assertThat(req.getBody().readUtf8()).contains("assertion=static-assertion-value");
    }
  }

  @Test
  void shouldNotCallSupplierInToString() {
    // Supplier that throws if called — toString must not invoke it
    java.util.function.Supplier<String> throwingSupplier = () -> {
      throw new RuntimeException("Supplier should not be called in toString");
    };

    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint("https://auth.example.com/token")
        .clientId("tostring-test")
        .clientSecretSupplier(throwingSupplier)
        .build();

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> config.toString());
  }

  @Test
  void shouldLogWarningOnNonCCRefresh() throws Exception {
    // PASSWORD grant: warning fires on second refresh
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig passwordConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("warn-password-" + UUID.randomUUID())
        .clientSecret("secret")
        .grantType(OAuth2GrantType.PASSWORD)
        .username("user")
        .password("pass")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(1))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(passwordConfig)) {
      // First fetch — no warning (currentToken is null, no prior token)
      mockWebServer.enqueue(successResponse("pwd-token-1", 5));
      manager.getToken();
      assertThat(manager.isRefreshWarningLogged()).isFalse();

      // Advance past expiry to force refresh
      testClock.advance(Duration.ofSeconds(6));
      mockWebServer.enqueue(successResponse("pwd-token-2", 3600));
      manager.getToken();

      assertThat(manager.isRefreshWarningLogged()).isTrue();
    }
  }

  @Test
  void shouldNotLogWarningForClientCredentials() throws Exception {
    // CC grant: no warning even after refresh
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig ccConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("warn-cc-" + UUID.randomUUID())
        .clientSecret("secret")
        .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(1))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(ccConfig)) {
      mockWebServer.enqueue(successResponse("cc-token-1", 5));
      manager.getToken();

      testClock.advance(Duration.ofSeconds(6));
      mockWebServer.enqueue(successResponse("cc-token-2", 3600));
      manager.getToken();

      assertThat(manager.isRefreshWarningLogged()).isFalse();
    }
  }

  @Test
  void shouldLogWarningOnlyOnce() throws Exception {
    // PASSWORD grant: flag stays true after multiple refreshes (logged once)
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig passwordConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("warn-once-" + UUID.randomUUID())
        .clientSecret("secret")
        .grantType(OAuth2GrantType.PASSWORD)
        .username("user")
        .password("pass")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(1))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(passwordConfig)) {
      // First fetch
      mockWebServer.enqueue(successResponse("once-token-1", 5));
      manager.getToken();

      // Second fetch — triggers warning
      testClock.advance(Duration.ofSeconds(6));
      mockWebServer.enqueue(successResponse("once-token-2", 5));
      manager.getToken();
      assertThat(manager.isRefreshWarningLogged()).isTrue();

      // Third fetch — warning already logged, flag stays true
      testClock.advance(Duration.ofSeconds(6));
      mockWebServer.enqueue(successResponse("once-token-3", 3600));
      manager.getToken();
      assertThat(manager.isRefreshWarningLogged()).isTrue();
    }
  }

  @Test
  void shouldEagerFetchTokenOnConstruction() throws Exception {
    // eagerFetch blocks in constructor; first getToken() returns without a second HTTP call
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"access_token": "eager-token", "token_type": "Bearer", "expires_in": 3600}
            """));

    TokenConfig eagerConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("eager-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .eagerFetch(true)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(eagerConfig)) {
      String token = manager.getToken();
      assertThat(token).isEqualTo("eager-token");
      assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }
  }

  @Test
  void shouldReturnEagerFetchedTokenOnFirstGetToken() throws Exception {
    // eagerFetch blocks in constructor, so getToken() returns cached token immediately
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"access_token": "eager-cached", "token_type": "Bearer", "expires_in": 3600}
            """));

    TokenConfig eagerConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("eager-cached-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .eagerFetch(true)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(eagerConfig)) {
      String token = manager.getToken();
      assertThat(token).isEqualTo("eager-cached");
      assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }
  }

  @Test
  void shouldWorkNormallyWithEagerFetchDisabled() throws Exception {
    // Default eagerFetch(false): no HTTP request until first getToken() call
    TokenConfig lazyConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("lazy-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .eagerFetch(false)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(lazyConfig)) {
      // No request made yet
      assertThat(mockWebServer.getRequestCount()).isEqualTo(0);

      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {"access_token": "lazy-token", "token_type": "Bearer", "expires_in": 3600}
              """));

      String token = manager.getToken();
      assertThat(token).isEqualTo("lazy-token");
      assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }
  }

  @Test
  void shouldThrowOnEagerFetchWithBadCredentials() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(401)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "invalid_client", "error_description": "Bad client credentials"}
            """));

    TokenConfig eagerConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("eager-bad-creds-" + UUID.randomUUID())
        .clientSecret("wrong-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .eagerFetch(true)
        .build();

    assertThatThrownBy(() -> new OAuth2TokenManager(eagerConfig))
        .isInstanceOf(InvalidCredentialsException.class)
        .hasMessageContaining("Bad client credentials");
  }

  @Test
  void shouldThrowOnEagerFetchWithServerError() {
    mockWebServer.enqueue(new MockResponse().setResponseCode(500));
    mockWebServer.enqueue(new MockResponse().setResponseCode(500));
    mockWebServer.enqueue(new MockResponse().setResponseCode(500));

    TokenConfig eagerConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("eager-500-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .eagerFetch(true)
        .build();

    assertThatThrownBy(() -> new OAuth2TokenManager(eagerConfig))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void shouldReturnCompletedFutureForCachedToken() throws Exception {
    // Prime the cache
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"access_token": "cached-async", "token_type": "Bearer", "expires_in": 3600}
            """));

    tokenManager.getToken();
    assertThat(mockWebServer.getRequestCount()).isEqualTo(1);

    // Async call should return an already-completed future from cache
    CompletableFuture<String> future = tokenManager.getTokenAsync();
    assertThat(future.isDone()).isTrue();
    assertThat(future.get()).isEqualTo("cached-async");

    // No additional HTTP request
    assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
  }

  @Test
  void shouldReturnTokenFromGetTokenAsync() throws Exception {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"access_token": "async-token", "token_type": "Bearer", "expires_in": 3600}
            """));

    String token = tokenManager.getTokenAsync().get(10, TimeUnit.SECONDS);
    assertThat(token).isEqualTo("async-token");
  }

  @Test
  void shouldCompleteFutureExceptionallyOnInvalidCredentials() throws Exception {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(401)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "invalid_client", "error_description": "Bad credentials"}
            """));

    CompletableFuture<String> future = tokenManager.getTokenAsync();

    assertThat(future)
        .failsWithin(Duration.ofSeconds(10))
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  void shouldGracefullyDegradeInAsyncPath() throws Exception {
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("async-degrade-" + UUID.randomUUID())
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
              {"access_token": "good-token", "token_type": "Bearer", "expires_in": 60}
              """));

      manager.getToken();

      // Advance into refresh threshold but before expiry (t=51, expires at t=60)
      testClock.advance(Duration.ofSeconds(51));

      // Server returns 500 — transient failure
      mockWebServer.enqueue(new MockResponse().setResponseCode(500));

      // Async path should degrade to cached token
      String fallback = manager.getTokenAsync().get(10, TimeUnit.SECONDS);
      assertThat(fallback).isEqualTo("good-token");
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldNotGracefullyDegradeForPermanentErrorsAsync() throws Exception {
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig testConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("async-perm-" + UUID.randomUUID())
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
              {"access_token": "good-token", "token_type": "Bearer", "expires_in": 60}
              """));

      manager.getToken();

      // Advance into refresh threshold but before expiry
      testClock.advance(Duration.ofSeconds(51));

      // Server returns 401 — permanent failure
      mockWebServer.enqueue(new MockResponse()
          .setResponseCode(401)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody("""
              {"error": "invalid_client", "error_description": "Credentials revoked"}
              """));

      // Async path should NOT degrade — permanent errors rethrow immediately
      CompletableFuture<String> future = manager.getTokenAsync();
      assertThat(future)
          .failsWithin(Duration.ofSeconds(10))
          .withThrowableOfType(ExecutionException.class)
          .withCauseExactlyInstanceOf(InvalidCredentialsException.class);
    } finally {
      manager.close();
    }
  }

  @Test
  void shouldReturnFailedFutureWhenClosed() throws Exception {
    tokenManager.close();

    CompletableFuture<String> future = tokenManager.getTokenAsync();

    assertThat(future)
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldLogWarningOnAuthorizationCodeRefresh() throws Exception {
    // AUTHORIZATION_CODE grant should trigger refresh warning on second fetch,
    // same as PASSWORD — coverage parity.
    MutableClock testClock = new MutableClock(Instant.now());

    TokenConfig authCodeConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("warn-authcode-" + UUID.randomUUID())
        .clientSecret("secret")
        .grantType(OAuth2GrantType.AUTHORIZATION_CODE)
        .authorizationCode("auth-code-123")
        .redirectUri("https://example.com/callback")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofSeconds(1))
        .clock(testClock)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(authCodeConfig)) {
      // First fetch — no warning
      mockWebServer.enqueue(successResponse("authcode-token-1", 5));
      manager.getToken();
      assertThat(manager.isRefreshWarningLogged()).isFalse();

      // Advance past expiry to force refresh
      testClock.advance(Duration.ofSeconds(6));
      mockWebServer.enqueue(successResponse("authcode-token-2", 3600));
      manager.getToken();

      assertThat(manager.isRefreshWarningLogged()).isTrue();
    }
  }
}
