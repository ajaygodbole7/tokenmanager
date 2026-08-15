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

import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for timeout and lifecycle fixes: close() no longer awaits an
 * in-flight call it could cancel, the internally-created client is bounded by an
 * overall call timeout (not just per-read timeouts), synchronous getToken()
 * degrades to the cached token on timeout instead of throwing, eager fetch
 * classifies DNS failures correctly, and Retry-After parsing clamps edge inputs.
 */
class TokenManagerTimeoutAndLifecycleTest extends AbstractMockServerTest {

  @Test
  void closeShouldReturnPromptlyWithHungRequestInFlight() throws Exception {
    try (TrustedOwnedServer owned = TrustedOwnedServer.start()) {
      owned.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

      // No .httpClient(...) — OAuth2TokenManager builds and owns its own client,
      // which is the only path where close() calls dispatcher().cancelAll().
      // maxRetryAttempts(1) matters: cancelAll() only aborts calls that exist at
      // the moment it runs — a retry would open a brand-new connection to the
      // still-hung server that cancelAll() never touched, defeating the test.
      TokenConfig config = TokenConfig.builder()
          .tokenEndpoint(owned.endpoint(TOKEN_ENDPOINT))
          .clientId("close-promptly-" + UUID.randomUUID())
          .clientSecret("test-secret")
          .httpTimeout(Duration.ofSeconds(20))
          .maxRetryAttempts(1)
          .refreshThreshold(REFRESH_THRESHOLD)
          .build();

      OAuth2TokenManager manager = new OAuth2TokenManager(config);
      try {
        manager.getTokenAsync(); // fires a refresh that will hang against NO_RESPONSE
        Thread.sleep(300); // let the request actually reach the server and hang there

        long start = System.nanoTime();
        manager.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
            .as("close() should cancel the in-flight call instead of awaiting its httpTimeout")
            .isLessThan(2000);
      } finally {
        manager.close();
      }
    }
  }

  @Test
  void closeShouldNotDispatchRetryAttemptsAfterCancellingInFlightCall() throws Exception {
    try (TrustedOwnedServer owned = TrustedOwnedServer.start()) {
      // Counts every request and hangs, so the first attempt is guaranteed to be
      // in-flight when close() runs. cancelAll() aborts it with a "Canceled"
      // IOException, which matches the retry predicate — without the closed-flag
      // short-circuit in the refresh supplier, the retry loop would dispatch a
      // brand-new request (never covered by cancelAll()) after close() returned.
      CountDownLatch firstRequestArrived = new CountDownLatch(1);
      // Released in the finally block so the dispatcher thread unblocks and the
      // server can shut down (a plain long sleep would hang server.shutdown()).
      CountDownLatch releaseServer = new CountDownLatch(1);
      AtomicInteger requestCount = new AtomicInteger();
      owned.server.setDispatcher(new Dispatcher() {
        @Override
        public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
          requestCount.incrementAndGet();
          firstRequestArrived.countDown();
          releaseServer.await(10, TimeUnit.SECONDS);
          return new MockResponse().setResponseCode(500);
        }
      });

      TokenConfig config = TokenConfig.builder()
          .tokenEndpoint(owned.endpoint(TOKEN_ENDPOINT))
          .clientId("close-no-retry-" + UUID.randomUUID())
          .clientSecret("test-secret")
          .httpTimeout(Duration.ofSeconds(20))
          .maxRetryAttempts(3)
          .initialRetryDelay(Duration.ofMillis(100))
          .refreshThreshold(REFRESH_THRESHOLD)
          .build();

      OAuth2TokenManager manager = new OAuth2TokenManager(config);
      try {
        manager.getTokenAsync();
        assertThat(firstRequestArrived.await(5, TimeUnit.SECONDS))
            .as("first refresh attempt should reach the server before close()")
            .isTrue();

        manager.close();

        // No settling sleep needed: close() awaits executor termination, and
        // without the closed-flag guard the retry loop dispatches attempt 2
        // ~150ms after cancelAll() — well inside that wait — so requestCount
        // is already 2 by the time close() returns in the regression case.
        assertThat(requestCount.get())
            .as("no retry attempt may dispatch a new HTTP request after close()")
            .isEqualTo(1);
      } finally {
        releaseServer.countDown();
        manager.close();
      }
    }
  }

  @Test
  void slowDripResponseIsBoundedByOverallCallTimeout() throws Exception {
    try (TrustedOwnedServer owned = TrustedOwnedServer.start()) {
      // ~2000 bytes at 8 bytes/100ms would take ~25s to fully transfer — far
      // longer than httpTimeout below — but per-read timeouts never fire because
      // data keeps trickling in. Only an overall call timeout bounds this.
      String body = "{\"access_token\": \"" + "x".repeat(2000)
          + "\", \"token_type\": \"Bearer\", \"expires_in\": 3600}";
      owned.server.enqueue(new MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
          .setBody(body)
          .throttleBody(8, 100, TimeUnit.MILLISECONDS));

      TokenConfig config = TokenConfig.builder()
          .tokenEndpoint(owned.endpoint(TOKEN_ENDPOINT))
          .clientId("call-timeout-" + UUID.randomUUID())
          .clientSecret("test-secret")
          .httpTimeout(Duration.ofSeconds(2))
          .maxRetryAttempts(1)
          .refreshThreshold(REFRESH_THRESHOLD)
          .build();

      OAuth2TokenManager manager = new OAuth2TokenManager(config);
      try {
        long start = System.nanoTime();
        assertThatThrownBy(manager::getToken).isInstanceOf(ServiceUnavailableException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
            .as("callTimeout should bound the whole call, not just gaps between reads")
            .isLessThan(5000);
      } finally {
        manager.close();
      }
    }
  }

  @Test
  void getTokenRacingCloseFailsInsteadOfReturningCachedToken() throws Exception {
    MutableClock clock = new MutableClock(Instant.now());

    // Short callTimeout so the refresh thread abandoned by close() dies quickly
    // and close()'s executor wait doesn't stretch the test.
    okhttp3.OkHttpClient shortTimeoutClient = httpClient.newBuilder()
        .callTimeout(Duration.ofSeconds(2))
        .build();

    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("close-race-degrade-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(Duration.ofSeconds(2))
        .maxRetryAttempts(1)
        .refreshThreshold(Duration.ofSeconds(30))
        .clock(clock)
        .httpClient(shortTimeoutClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(config);
    try {
      mockWebServer.enqueue(successResponse("cached-before-close", 3600));
      assertThat(manager.getToken()).isEqualTo("cached-before-close");
      assertThat(mockWebServer.takeRequest(5, TimeUnit.SECONDS)).isNotNull();

      // Inside the refresh threshold but not expired — the exact window where
      // graceful degradation would otherwise serve the cached token.
      clock.advance(Duration.ofSeconds(3600 - 10));

      mockWebServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
      var future = manager.getTokenAsync();
      assertThat(mockWebServer.takeRequest(5, TimeUnit.SECONDS))
          .as("refresh should be in flight before close()")
          .isNotNull();

      manager.close();

      // The refresh was aborted by close(); degradation must not hand out a
      // token from a closed manager even though the cached one is unexpired.
      assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(ServiceUnavailableException.class);
    } finally {
      manager.close();
    }
  }

  @Test
  void syncGetTokenReturnsCachedTokenInsteadOfThrowingOnTimeout() throws Exception {
    MutableClock clock = new MutableClock(Instant.now());

    // httpTimeout is short (drives computeOverallTimeout ~6s), but the shared
    // test httpClient keeps its own 15s read timeout — so getToken()'s outer
    // timeout fires first while the HTTP call is still (harmlessly) in flight.
    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("sync-degrade-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(Duration.ofSeconds(1))
        .maxRetryAttempts(1)
        .refreshThreshold(Duration.ofSeconds(5))
        .clock(clock)
        .httpClient(httpClient)
        .build();

    OAuth2TokenManager manager = new OAuth2TokenManager(config);
    try {
      mockWebServer.enqueue(successResponse("cached-during-outage", 3600));
      String initial = manager.getToken();
      assertThat(initial).isEqualTo("cached-during-outage");

      // Cross the refresh threshold so the next getToken() triggers a refresh,
      // while the cached token itself remains unexpired.
      clock.advance(Duration.ofSeconds(3600 - 10));

      mockWebServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

      String duringOutage = manager.getToken();
      assertThat(duringOutage)
          .as("getToken() should degrade to the cached token on timeout, not throw")
          .isEqualTo("cached-during-outage");
    } finally {
      manager.close();
    }
  }

  @Test
  void eagerFetchClassifiesDnsFailureAsInvalidEndpointException() {
    // ".invalid" is an IANA-reserved TLD guaranteed never to resolve — no
    // MockWebServer needed, the failure happens at DNS lookup before any TLS.
    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint("https://this-host-should-not-resolve.invalid/oauth/token")
        .clientId("eager-dns-failure-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(Duration.ofSeconds(5))
        .eagerFetch(true)
        .build();

    assertThatThrownBy(() -> new OAuth2TokenManager(config))
        .isInstanceOf(InvalidEndpointException.class);
  }

  @Test
  void retryAfterHttpDateInThePastClampsToZero() {
    // RFC-850 format ("Sunday, 06-Nov-94 08:49:37 GMT"), parsed by OkHttp's
    // Headers.getInstant per RFC 9110 §5.6.7. 1994 is unambiguously in the past,
    // so the resulting delta is negative and must clamp to Duration.ZERO
    // (not null — the header WAS successfully parsed).
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(429)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .addHeader("Retry-After", "Sunday, 06-Nov-94 08:49:37 GMT")
        .setBody("{\"error\": \"rate_limited\"}"));

    assertThatThrownBy(tokenManager::getToken)
        .isInstanceOf(RateLimitedException.class)
        .satisfies(ex -> {
          Duration retryAfter = ((RateLimitedException) ex).getRetryAfter();
          assertThat(retryAfter).isEqualTo(Duration.ZERO);
        });
  }

  @Test
  void retryAfterNegativeSecondsClampsToZero() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(429)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .addHeader("Retry-After", "-5")
        .setBody("{\"error\": \"rate_limited\"}"));

    assertThatThrownBy(tokenManager::getToken)
        .isInstanceOf(RateLimitedException.class)
        .satisfies(ex -> {
          Duration retryAfter = ((RateLimitedException) ex).getRetryAfter();
          assertThat(retryAfter).isEqualTo(Duration.ZERO);
        });
  }

  /**
   * A fresh HTTPS MockWebServer with a self-signed cert, whose cert is installed
   * into the JVM's default trust store (via the {@code javax.net.ssl.trustStore}
   * system property) for the lifetime of this resource.
   *
   * <p>OAuth2TokenManager's internally-created OkHttpClient has no configuration
   * hook for a custom trust store, so a test that needs an OWNED client
   * ({@code config.getHttpClient() == null}) to reach a self-signed test server
   * cannot reuse the shared {@code AbstractMockServerTest} fixture, which only
   * configures trust for its own pre-built client.
   */
  private static final class TrustedOwnedServer implements AutoCloseable {
    final MockWebServer server;
    private final File trustStoreFile;
    private final String previousTrustStore;
    private final String previousTrustStorePassword;
    private final String previousTrustStoreType;

    private TrustedOwnedServer(MockWebServer server, File trustStoreFile,
        String previousTrustStore, String previousTrustStorePassword,
        String previousTrustStoreType) {
      this.server = server;
      this.trustStoreFile = trustStoreFile;
      this.previousTrustStore = previousTrustStore;
      this.previousTrustStorePassword = previousTrustStorePassword;
      this.previousTrustStoreType = previousTrustStoreType;
    }

    static TrustedOwnedServer start() throws Exception {
      HeldCertificate rootCa = new HeldCertificate.Builder().certificateAuthority(1).build();
      HeldCertificate serverCert = new HeldCertificate.Builder()
          .addSubjectAlternativeName("localhost")
          // Endpoints are forced to 127.0.0.1 (see endpoint()) to dodge macOS's
          // IPv6-first loopback resolution, so the cert needs a matching IP SAN
          // too or hostname verification fails before the test scenario runs.
          .addSubjectAlternativeName("127.0.0.1")
          .signedBy(rootCa)
          .build();
      HandshakeCertificates serverCerts = new HandshakeCertificates.Builder()
          .heldCertificate(serverCert)
          .build();

      MockWebServer server = new MockWebServer();
      server.useHttps(serverCerts.sslSocketFactory(), false);
      server.start();

      KeyStore trustStore = KeyStore.getInstance("PKCS12");
      trustStore.load(null, null);
      trustStore.setCertificateEntry("test-ca", rootCa.certificate());
      File trustStoreFile = File.createTempFile("review-fixes-truststore", ".p12");
      try (FileOutputStream out = new FileOutputStream(trustStoreFile)) {
        trustStore.store(out, "changeit".toCharArray());
      }

      String previousTrustStore = System.getProperty("javax.net.ssl.trustStore");
      String previousTrustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");
      String previousTrustStoreType = System.getProperty("javax.net.ssl.trustStoreType");
      System.setProperty("javax.net.ssl.trustStore", trustStoreFile.getAbsolutePath());
      System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
      System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");

      return new TrustedOwnedServer(server, trustStoreFile,
          previousTrustStore, previousTrustStorePassword, previousTrustStoreType);
    }

    /** Force IPv4 to avoid IPv6 ConnectException masking the real behavior under test. */
    String endpoint(String path) {
      return server.url(path).toString().replace("localhost", "127.0.0.1");
    }

    @Override
    public void close() throws Exception {
      restoreProperty("javax.net.ssl.trustStore", previousTrustStore);
      restoreProperty("javax.net.ssl.trustStorePassword", previousTrustStorePassword);
      restoreProperty("javax.net.ssl.trustStoreType", previousTrustStoreType);
      trustStoreFile.delete();
      server.shutdown();
    }

    private static void restoreProperty(String key, String previous) {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }
}
