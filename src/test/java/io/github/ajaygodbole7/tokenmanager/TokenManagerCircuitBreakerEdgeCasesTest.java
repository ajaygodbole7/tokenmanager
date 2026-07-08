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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

/**
 * Deeper circuit-breaker state-machine edges not covered by the basic recovery and
 * sliding-window regression tests in {@link TokenManagerReviewFixesTest}: a failed
 * HALF_OPEN probe re-opening the breaker (and a subsequent recovery still working),
 * mixed success/failure/ignored outcomes correctly not tripping the breaker, and
 * concurrent invalidate()/getToken() traffic surviving without hangs or corruption.
 */
class TokenManagerCircuitBreakerEdgeCasesTest extends AbstractMockServerTest {

  private MockResponse serverError() {
    return new MockResponse()
        .setResponseCode(500)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "server_error", "error_description": "boom"}
            """);
  }

  private MockResponse invalidClient() {
    return new MockResponse()
        .setResponseCode(401)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "invalid_client", "error_description": "bad credentials"}
            """);
  }

  private MockResponse rateLimited(String retryAfterSeconds) {
    return new MockResponse()
        .setResponseCode(429)
        .addHeader("Retry-After", retryAfterSeconds)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "rate_limited"}
            """);
  }

  @Test
  void shouldReopenCircuitBreakerWhenHalfOpenProbeFailsThenRecoverOnSecondProbe() throws Exception {
    OAuth2TokenManager mgr = new OAuth2TokenManager(TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("cb-half-open-" + UUID.randomUUID())
        .clientSecret("secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .circuitBreakerMinimumCalls(2)
        .circuitBreakerWaitDuration(Duration.ofMillis(300))
        .httpClient(httpClient)
        .build());
    try {
      // Open the breaker with 2 consecutive failures.
      mockWebServer.enqueue(serverError());
      mockWebServer.enqueue(serverError());
      for (int i = 0; i < 2; i++) {
        assertThatThrownBy(mgr::getToken).isInstanceOf(ServiceUnavailableException.class);
      }
      int requestsWhenOpen = mockWebServer.getRequestCount();
      assertThatThrownBy(mgr::getToken)
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessageContaining("Circuit breaker is open");
      assertThat(mockWebServer.getRequestCount())
          .as("fast-fail while open must not reach the server")
          .isEqualTo(requestsWhenOpen);

      // Wait out the open window; the HALF_OPEN probe itself fails, so the
      // breaker must go straight back to OPEN rather than staying half-open
      // or (incorrectly) closing.
      Thread.sleep(400);
      mockWebServer.enqueue(serverError());
      int requestsBeforeProbe = mockWebServer.getRequestCount();
      assertThatThrownBy(mgr::getToken).isInstanceOf(ServiceUnavailableException.class);
      assertThat(mockWebServer.getRequestCount())
          .as("failed HALF_OPEN probe must actually reach the server")
          .isEqualTo(requestsBeforeProbe + 1);

      int requestsAfterFailedProbe = mockWebServer.getRequestCount();
      assertThatThrownBy(mgr::getToken)
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessageContaining("Circuit breaker is open");
      assertThat(mockWebServer.getRequestCount())
          .as("re-opened breaker must fast-fail again")
          .isEqualTo(requestsAfterFailedProbe);

      // Wait out the open window a second time; this probe succeeds, so the
      // breaker must close and recovery must actually work a second time.
      Thread.sleep(400);
      mockWebServer.enqueue(successResponse("recovered-again", 3600));
      assertThat(mgr.getToken()).isEqualTo("recovered-again");
    } finally {
      mgr.close();
    }
  }

  @Test
  void shouldNotOpenCircuitBreakerWhenCredentialErrorInterruptsFailureStreak() throws Exception {
    OAuth2TokenManager mgr = new OAuth2TokenManager(TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("cb-mixed-" + UUID.randomUUID())
        .clientSecret("secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .circuitBreakerMinimumCalls(3)
        .httpClient(httpClient)
        .build());
    try {
      // InvalidCredentialsException does not match the CB's recordException
      // predicate, so it counts as a CB success — the failure rate over this
      // 3-call window is 2/3 (~66.7%), never reaching the 100% threshold.
      mockWebServer.enqueue(serverError());
      assertThatThrownBy(mgr::getToken).isInstanceOf(ServiceUnavailableException.class);

      mockWebServer.enqueue(invalidClient());
      assertThatThrownBy(mgr::getToken).isInstanceOf(InvalidCredentialsException.class);

      mockWebServer.enqueue(serverError());
      assertThatThrownBy(mgr::getToken).isInstanceOf(ServiceUnavailableException.class);

      int requestsBeforeSuccess = mockWebServer.getRequestCount();
      mockWebServer.enqueue(successResponse("still-closed-token", 3600));
      assertThat(mgr.getToken())
          .as("breaker must still be closed — this call must actually reach the server")
          .isEqualTo("still-closed-token");
      assertThat(mockWebServer.getRequestCount()).isEqualTo(requestsBeforeSuccess + 1);
    } finally {
      mgr.close();
    }
  }

  @Test
  void shouldNeverOpenCircuitBreakerFromRepeated429Responses() throws Exception {
    MutableClock clock = new MutableClock(Instant.now());
    OAuth2TokenManager mgr = new OAuth2TokenManager(TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("cb-429-" + UUID.randomUUID())
        .clientSecret("secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .circuitBreakerMinimumCalls(3)
        .clock(clock)
        .httpClient(httpClient)
        .build());
    try {
      // RateLimitedException is CB-ignored entirely (neither success nor failure),
      // so it must never accumulate toward opening the breaker. Advance the clock
      // past each 429's own cooldown between calls so every call actually reaches
      // the circuit breaker / server instead of short-circuiting on the cooldown.
      for (int i = 0; i < 5; i++) {
        mockWebServer.enqueue(rateLimited("1"));
        assertThatThrownBy(mgr::getToken).isInstanceOf(RateLimitedException.class);
        clock.advance(Duration.ofSeconds(2));
      }

      int requestsBeforeSuccess = mockWebServer.getRequestCount();
      mockWebServer.enqueue(successResponse("post-429-token", 3600));
      assertThat(mgr.getToken())
          .as("5 consecutive 429s must not have opened the breaker")
          .isEqualTo("post-429-token");
      assertThat(mockWebServer.getRequestCount()).isEqualTo(requestsBeforeSuccess + 1);
    } finally {
      mgr.close();
    }
  }

  @Test
  void shouldSurviveConcurrentInvalidateAndGetTokenWithoutHangingOrCorruption() throws Exception {
    // An always-succeed dispatcher, since a fixed enqueue() count can't cover
    // indeterminate concurrent load.
    mockWebServer.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        return new MockResponse()
            .setResponseCode(200)
            .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
            .setBody("""
                {"access_token": "stress-token", "token_type": "Bearer", "expires_in": 60}
                """);
      }
    });

    OAuth2TokenManager mgr = new OAuth2TokenManager(TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("stress-" + UUID.randomUUID())
        .clientSecret("secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build());

    int workerThreads = 20;
    int iterationsPerThread = 50;
    ExecutorService pool = Executors.newFixedThreadPool(workerThreads + 1);
    List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger successCount = new AtomicInteger();

    try {
      List<Future<?>> workers = new ArrayList<>();
      for (int t = 0; t < workerThreads; t++) {
        workers.add(pool.submit(() -> {
          for (int i = 0; i < iterationsPerThread; i++) {
            try {
              String token = mgr.getToken();
              assertThat(token).isNotNull();
              successCount.incrementAndGet();
            } catch (TokenException | IllegalStateException expected) {
              // Documented failure modes under concurrent invalidate() — not a bug.
            } catch (Throwable t2) {
              unexpected.add(t2);
            }
          }
        }));
      }

      Future<?> invalidator = pool.submit(() -> {
        for (int i = 0; i < 20; i++) {
          mgr.invalidate();
          try {
            Thread.sleep(5);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
        }
      });

      for (Future<?> f : workers) {
        f.get(30, TimeUnit.SECONDS);
      }
      invalidator.get(5, TimeUnit.SECONDS);

      assertThat(unexpected).as("no undocumented exceptions under concurrent load").isEmpty();
      assertThat(successCount.get()).isGreaterThan(0);

      // The manager must still be usable after the stress run.
      assertThat(mgr.getToken()).isEqualTo("stress-token");
    } finally {
      pool.shutdownNow();
      mgr.close();
    }
  }
}
