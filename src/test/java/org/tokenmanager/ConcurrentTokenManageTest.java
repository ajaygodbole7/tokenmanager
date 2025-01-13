package org.tokenmanager;

import java.util.Collections;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Tests concurrent behavior of the TokenManager. These tests focus on:
 * 1. Thread safety of token requests and caching
 * 2. Token refresh synchronization
 * 3. Shutdown behavior with pending requests
 * 4. Request queueing during refresh operations
 */
class TokenManagerConcurrencyTest {
  private static final String TOKEN_ENDPOINT = "/oauth/token";
  private static final String CONTENT_TYPE_HEADER = "Content-Type";
  private static final String CONTENT_TYPE_JSON = "application/json";
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration REFRESH_THRESHOLD = Duration.ofSeconds(30);
  private static final String EXPECTED_TOKEN = "test-token";
  private static final String EXPIRED_TOKEN = "expired-token";
  private static final String REFRESHED_TOKEN = "refreshed-token";

  private MockWebServer mockWebServer;
  private OkHttpClient httpClient;
  private TokenConfig tokenConfig;
  private Oauth2TokenManager tokenManager;

  @BeforeEach
  void setUp() throws IOException {
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

    mockWebServer = new MockWebServer();
    mockWebServer.useHttps(serverCerts.sslSocketFactory(), false);
    mockWebServer.start();

    httpClient = new OkHttpClient.Builder()
        .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager())
        .hostnameVerifier((hostname, session) -> true)
        .connectTimeout(HTTP_TIMEOUT)
        .readTimeout(HTTP_TIMEOUT)
        .writeTimeout(HTTP_TIMEOUT)
        .build();

    tokenConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("test-client")
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    tokenManager = new Oauth2TokenManager(tokenConfig);
  }

  @AfterEach
  void tearDown() throws IOException {
    tokenManager.close();
    mockWebServer.shutdown();
    httpClient.dispatcher().executorService().shutdown();
    httpClient.connectionPool().evictAll();
  }

  /**
   * Tests the basic concurrent access pattern where multiple threads request a token simultaneously.
   * Verifies that:
   * - All threads get the same token
   * - Only one HTTP request is made
   * - Token caching works correctly under concurrent access
   */
  @Test
  void shouldReturnSameTokenForConcurrentRequests() throws Exception {
    // Given
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody(String.format("""
                {
                    "access_token": "%s",
                    "token_type": "Bearer",
                    "expires_in": 3600
                }
                """, EXPECTED_TOKEN)));

    int threadCount = 10;
    Set<String> uniqueTokens = ConcurrentHashMap.newKeySet();
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);

    // When
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          String token = tokenManager.getToken();
          uniqueTokens.add(token);
        } finally {
          completionLatch.countDown();
        }
      });
    }

    boolean allThreadsCompleted = completionLatch.await(10, TimeUnit.SECONDS);

    // Then
    assertThat(allThreadsCompleted)
        .as("All threads should complete within timeout")
        .isTrue();

    assertThat(uniqueTokens)
        .as("All threads should receive the same token")
        .hasSize(1)
        .containsExactly(EXPECTED_TOKEN);

    assertThat(mockWebServer.getRequestCount())
        .as("Only one HTTP request should be made")
        .isEqualTo(1);

    executor.shutdown();
    boolean executorTerminated = executor.awaitTermination(5, TimeUnit.SECONDS);
    assertThat(executorTerminated)
        .as("Executor should terminate gracefully")
        .isTrue();
  }

  /**
   * Tests concurrent access during token refresh.
   * Verifies that:
   * - Multiple threads requesting an expired token trigger only one refresh
   * - All threads receive the same refreshed token
   * - Proper synchronization during refresh operation
   */
  @Test
  void shouldRefreshExpiredTokenOnlyOnce() throws Exception {
    // Given
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody(String.format("""
                {
                    "access_token": "%s",
                    "token_type": "Bearer",
                    "expires_in": 0
                }
                """, EXPIRED_TOKEN)));

    tokenManager.getToken(); // Get initial expired token

    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody(String.format("""
                {
                    "access_token": "%s",
                    "token_type": "Bearer",
                    "expires_in": 3600
                }
                """, REFRESHED_TOKEN)));

    int threadCount = 10;
    Set<String> uniqueTokens = ConcurrentHashMap.newKeySet();
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);

    // When
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          String token = tokenManager.getToken();
          uniqueTokens.add(token);
        } finally {
          completionLatch.countDown();
        }
      });
    }

    boolean allThreadsCompleted = completionLatch.await(10, TimeUnit.SECONDS);

    // Then
    assertThat(allThreadsCompleted)
        .as("All threads should complete within timeout")
        .isTrue();

    assertThat(uniqueTokens)
        .as("All threads should receive the same refreshed token")
        .hasSize(1)
        .containsExactly(REFRESHED_TOKEN);

    assertThat(mockWebServer.getRequestCount())
        .as("Should make exactly two requests - one for initial token and one for refresh")
        .isEqualTo(2);

    executor.shutdown();
    boolean executorTerminated = executor.awaitTermination(5, TimeUnit.SECONDS);
    assertThat(executorTerminated)
        .as("Executor should terminate gracefully")
        .isTrue();
  }

  /**
   * Tests shutdown behavior while requests are in progress.
   * Verifies that:
   * - Pending requests are properly terminated
   * - Appropriate exceptions are thrown
   * - Resources are cleaned up correctly
   */
  @Test
  void shouldHandleShutdownWithPendingRequests() throws Exception {
    // Given
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBodyDelay(2, TimeUnit.SECONDS) // Simulate slow response
                              .setBody(String.format("""
                {
                    "access_token": "%s",
                    "token_type": "Bearer",
                    "expires_in": 3600
                }
                """, EXPECTED_TOKEN)));

    int threadCount = 5;
    List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch requestStartedLatch = new CountDownLatch(threadCount);

    // When
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          requestStartedLatch.countDown();
          tokenManager.getToken();
        } catch (Exception e) {
          exceptions.add(e);
        }
      });
    }

    // Wait for requests to start
    boolean requestsStarted = requestStartedLatch.await(5, TimeUnit.SECONDS);
    assertThat(requestsStarted).as("All requests should start").isTrue();

    // Shutdown token manager while requests are in progress
    Thread.sleep(100); // Brief delay to ensure requests are processing
    tokenManager.close();

    // Then
    assertThat(exceptions)
        .as("Pending requests should fail with appropriate exceptions")
        .isNotEmpty()
        .allMatch(e -> e instanceof ServiceUnavailableException
            || e instanceof CancellationException);

    executor.shutdown();
    boolean executorTerminated = executor.awaitTermination(5, TimeUnit.SECONDS);
    assertThat(executorTerminated)
        .as("Executor should terminate gracefully")
        .isTrue();
  }


  /**
   * Tests that multiple threads requesting tokens during a refresh operation
   * all wait and receive the same refreshed token.
   */
  @Test
  void shouldQueueRequestsDuringRefresh() throws Exception {
    // Given - Set up an expired token
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "access_token": "expired-token",
                "token_type": "Bearer",
                "expires_in": 0
            }
            """));

    // Get the initial expired token
    tokenManager.getToken();

    // Set up a slow refresh response
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBodyDelay(1, TimeUnit.SECONDS) // Slow response to ensure threads queue up
                              .setBody("""
            {
                "access_token": "refreshed-token",
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """));

    // When - Multiple threads request token during refresh
    int threadCount = 5;
    Set<String> tokensReceived = ConcurrentHashMap.newKeySet();
    CountDownLatch threadsComplete = new CountDownLatch(threadCount);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          String token = tokenManager.getToken();
          tokensReceived.add(token);
        } finally {
          threadsComplete.countDown();
        }
      });
    }

    // Then - Wait for all threads and verify results
    boolean completed = threadsComplete.await(10, TimeUnit.SECONDS);
    assertThat(completed)
        .as("All threads should complete")
        .isTrue();

    // Verify all threads got the same refreshed token
    assertThat(tokensReceived)
        .as("All threads should get the same refreshed token")
        .hasSize(1)
        .containsExactly("refreshed-token");

    // Verify only two requests were made (initial + refresh)
    assertThat(mockWebServer.getRequestCount())
        .as("Should make exactly two requests - initial + refresh")
        .isEqualTo(2);

    // Cleanup
    executor.shutdown();
    boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
    assertThat(terminated)
        .as("Executor should terminate gracefully")
        .isTrue();
  }

  /**
   * Tests that TokenManager correctly handles token expiration and refresh
   * when multiple threads are requesting tokens.
   */
  @Test
  void shouldHandleRapidExpirationCycles() throws Exception {
    // Given - Configure token manager with short expiration
    TokenConfig shortExpirationConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("test-client")
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(Duration.ofMillis(100)) // Short refresh threshold
        .httpClient(httpClient)
        .build();

    Oauth2TokenManager timeoutManager = new Oauth2TokenManager(shortExpirationConfig);

    // Set up first token that will expire quickly
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "access_token": "first-token",
                "token_type": "Bearer",
                "expires_in": 1
            }
            """));

    // Set up refreshed token response
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

    // Get first token and wait for it to expire
    String firstToken = timeoutManager.getToken();
    assertThat(firstToken).isEqualTo("first-token");

    // Wait for token to expire
    Thread.sleep(1100);

    // When - Multiple threads request token after expiration
    int threadCount = 5;
    Set<String> tokens = ConcurrentHashMap.newKeySet();
    CountDownLatch complete = new CountDownLatch(threadCount);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          String token = timeoutManager.getToken();
          tokens.add(token);
        } finally {
          complete.countDown();
        }
      });
    }

    // Then - Wait for all threads and verify results
    complete.await(5, TimeUnit.SECONDS);

    // All threads should get the refreshed token
    assertThat(tokens)
        .as("All threads should get the refreshed token")
        .hasSize(1)
        .containsExactly("refreshed-token");

    // Should see exactly two requests
    assertThat(mockWebServer.getRequestCount())
        .as("Should make exactly two requests - initial + refresh")
        .isEqualTo(2);

    // Cleanup
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
    timeoutManager.close();
  }

  /**
   * Tests TokenManager's behavior during concurrent refresh attempts with mixed success/failure.
   * Verifies that:
   * - Failed refresh attempts don't affect subsequent attempts
   * - Token manager recovers after initial failures
   * - Error handling is thread-safe
   */
  @Test
  void shouldHandleMixedRefreshResults() throws Exception {
    // Given - Set up initial valid token
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody(String.format("""
            {
                "access_token": "%s",
                "token_type": "Bearer",
                "expires_in": 0
            }
            """, EXPIRED_TOKEN)));

    // First wave of threads will trigger refresh and get errors
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(500)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "error": "server_error",
                "error_description": "Internal server error"
            }
            """));

    // Second wave of threads will get success
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody(String.format("""
            {
                "access_token": "%s",
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """, REFRESHED_TOKEN)));

    // Get initial expired token
    tokenManager.getToken();

    // When - First wave of threads (will encounter error)
    int threadCount = 5;
    Set<String> uniqueTokens = ConcurrentHashMap.newKeySet();
    List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch firstWaveComplete = new CountDownLatch(threadCount);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount * 2);

    // Launch first wave to hit the error
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          tokenManager.getToken();
        } catch (Exception e) {
          exceptions.add(e);
        } finally {
          firstWaveComplete.countDown();
        }
      });
    }

    // Wait for first wave to complete
    firstWaveComplete.await(5, TimeUnit.SECONDS);

    // Second wave of threads (should succeed after retry)
    CountDownLatch secondWaveComplete = new CountDownLatch(threadCount);

    // Brief delay to allow retry mechanism to reset
    Thread.sleep(100);

    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          String token = tokenManager.getToken();
          uniqueTokens.add(token);
        } catch (Exception e) {
          exceptions.add(e);
        } finally {
          secondWaveComplete.countDown();
        }
      });
    }

    // Then
    boolean completed = secondWaveComplete.await(10, TimeUnit.SECONDS);
    assertThat(completed)
        .as("All threads should complete")
        .isTrue();

    // Some threads should fail with ServiceUnavailableException
    assertThat(exceptions)
        .as("First wave of requests should fail with ServiceUnavailableException")
        .isNotEmpty()
        .allMatch(e -> e instanceof ServiceUnavailableException);

    // Second wave should succeed
    assertThat(uniqueTokens)
        .as("Second wave should get the refreshed token")
        .containsExactly(REFRESHED_TOKEN);

    // Verify total request count
    assertThat(mockWebServer.getRequestCount())
        .as("Should see initial request + failed refresh + successful refresh")
        .isEqualTo(3);

    executor.shutdown();
    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
        .as("Executor should terminate gracefully")
        .isTrue();
  }

  /**
   * Tests that when TokenManager is shut down during a refresh operation,
      * waiting threads receive appropriate exceptions and resources are cleaned up.
      */
  @Test
  void shouldHandleShutdownDuringRefresh() throws Exception {
    // Given - Set up an expired token
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "access_token": "expired-token",
                "token_type": "Bearer",
                "expires_in": 0
            }
            """));

    tokenManager.getToken(); // Get expired token

    // Set up a slow refresh response
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBodyDelay(2, TimeUnit.SECONDS) // Slow response to ensure we can shutdown during refresh
                              .setBody("""
            {
                "access_token": "new-token",
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """));

    // When - Start a thread that will get caught in refresh
    Exception[] caughtException = new Exception[1];
    Thread thread = new Thread(() -> {
      try {
        tokenManager.getToken();
        fail("Should have thrown exception due to shutdown");
      } catch (Exception e) {
        caughtException[0] = e;
      }
    });

    // Start thread and give it time to begin refresh
    thread.start();
    Thread.sleep(200);

    // Then shutdown while refresh is in progress
    tokenManager.close();

    // Wait for thread to complete
    thread.join(5000);

    // Verify appropriate exception was thrown
    assertThat(caughtException[0])
        .isInstanceOf(ServiceUnavailableException.class)
        .hasCauseInstanceOf(CancellationException.class);

    // Verify thread is not alive
    assertThat(thread.isAlive())
        .as("Thread should have completed")
        .isFalse();
  }

  /**
   * Tests TokenManager's behavior when multiple threads encounter network timeouts.
   * Verifies that:
   * - Timeout handling is thread-safe
   * - All waiting threads receive appropriate exceptions
   * - Token state remains consistent after timeout
   * - Subsequent requests can succeed after timeout
   */
  @Test
  void shouldHandleNetworkTimeoutsDuringConcurrentAccess() throws Exception {
    // Given - Configure token manager with short timeout
    TokenConfig shortTimeoutConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("test-client")
        .clientSecret("test-secret")
        .httpTimeout(Duration.ofMillis(500)) // Short timeout
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    Oauth2TokenManager timeoutManager = new Oauth2TokenManager(shortTimeoutConfig);

    // Set up response that will timeout
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBodyDelay(1, TimeUnit.SECONDS) // Delay longer than timeout
                              .setBody("""
            {
                "access_token": "test-token",
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """));

    // When - Multiple threads request tokens
    int threadCount = 5;
    List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch complete = new CountDownLatch(threadCount);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          timeoutManager.getToken();
          fail("Should timeout");
        } catch (Exception e) {
          exceptions.add(e);
        } finally {
          complete.countDown();
        }
      });
    }

    // Then - Wait for all threads and verify timeouts
    boolean completed = complete.await(5, TimeUnit.SECONDS);
    assertThat(completed)
        .as("All threads should complete")
        .isTrue();

    // Verify all threads got timeout exceptions
    assertThat(exceptions)
        .as("All threads should get timeout exceptions")
        .hasSize(threadCount)
        .allMatch(e -> e instanceof ServiceUnavailableException
            && e.getCause() instanceof TimeoutException);

    // Cleanup
    executor.shutdown();
    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
        .as("Executor should terminate gracefully")
        .isTrue();

    timeoutManager.close();
  }

  /**
   * Tests TokenManager's behavior under heavy lock contention.
   * Verifies that:
   * - Lock fairness works under load
   * - No threads are starved
   * - Resource usage remains stable
   * - All threads eventually get access
   */
  /**
   * Tests that multiple threads can successfully get tokens when competing
   * for the lock during refresh.
   */
  @Test
  void shouldHandleHeavyLockContentionWithoutStarvation() throws Exception {
    // Given - Get expired token first
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "access_token": "expired-token",
                "token_type": "Bearer",
                "expires_in": 0
            }
            """));

    tokenManager.getToken();

    // Set up slow refresh response to increase contention
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBodyDelay(1, TimeUnit.SECONDS)
                              .setBody("""
            {
                "access_token": "refreshed-token",
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """));

    // When - Launch many threads to create contention
    int threadCount = 20;
    Set<String> tokens = ConcurrentHashMap.newKeySet();
    CountDownLatch complete = new CountDownLatch(threadCount);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          String token = tokenManager.getToken();
          tokens.add(token);
        } finally {
          complete.countDown();
        }
      });
    }

    // Then - Wait for all threads and verify results
    boolean allComplete = complete.await(10, TimeUnit.SECONDS);
    assertThat(allComplete)
        .as("All threads should complete")
        .isTrue();

    // All threads should get the same token
    assertThat(tokens)
        .as("All threads should get the same token")
        .hasSize(1)
        .containsExactly("refreshed-token");

    // Should see exactly two requests
    assertThat(mockWebServer.getRequestCount())
        .as("Should make exactly two requests - initial + refresh")
        .isEqualTo(2);

    // Cleanup
    executor.shutdown();
    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
        .as("Executor should terminate gracefully")
        .isTrue();
  }

  /**
   * Tests that a thread can be interrupted while waiting for token refresh
   */
  @Test
  void shouldHandleThreadInterruptsDuringConcurrentOperations() throws Exception {
    // Get an expired token to force refresh
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "access_token": "expired-token",
                "token_type": "Bearer",
                "expires_in": 0
            }
            """));

    tokenManager.getToken();

    // Configure a very slow refresh response
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBodyDelay(3, TimeUnit.SECONDS)
                              .setBody("""
            {
                "access_token": "new-token",
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """));

    // Track the exception from the interrupted thread
    Exception[] caughtException = new Exception[1];

    // Create and start a thread that will be interrupted
    Thread thread = new Thread(() -> {
      try {
        tokenManager.getToken();
        fail("Thread should have been interrupted");
      } catch (Exception e) {
        caughtException[0] = e;
      }
    });

    thread.start();

    // Give thread time to start and begin waiting for refresh
    Thread.sleep(200);

    // Interrupt the thread
    thread.interrupt();

    // Wait for thread to finish
    thread.join(5000);

    // Verify we got the expected exception
    assertThat(caughtException[0])
        .isInstanceOf(ServiceUnavailableException.class)
        .hasCauseInstanceOf(InterruptedException.class);

    // Verify thread is not alive
    assertThat(thread.isAlive()).isFalse();
  }

  /**
   * Tests that threads can timeout while waiting for token refresh
   */
  @Test
  void shouldHandleTimeoutDuringRefresh() throws Exception {
    // Configure short timeout for test
    TokenConfig shortTimeoutConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("test-client")
        .clientSecret("test-secret")
        .httpTimeout(Duration.ofMillis(500)) // Very short timeout
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    Oauth2TokenManager timeoutManager = new Oauth2TokenManager(shortTimeoutConfig);

    // Get an expired token to force refresh
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "access_token": "expired-token",
                "token_type": "Bearer",
                "expires_in": 0
            }
            """));

    timeoutManager.getToken();

    // Configure a very slow refresh response
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBodyDelay(2, TimeUnit.SECONDS) // Delay longer than timeout
                              .setBody("""
            {
                "access_token": "new-token",
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """));

    // Track the exception from the thread
    Exception[] caughtException = new Exception[1];

    // Create and start a thread that should timeout
    Thread thread = new Thread(() -> {
      try {
        timeoutManager.getToken();
        fail("Thread should have timed out");
      } catch (Exception e) {
        caughtException[0] = e;
      }
    });

    thread.start();

    // Wait for thread to finish
    thread.join(5000);

    // Verify we got timeout exception
    assertThat(caughtException[0])
        .isInstanceOf(ServiceUnavailableException.class)
        .hasCauseInstanceOf(TimeoutException.class);

    // Verify thread is not alive
    assertThat(thread.isAlive()).isFalse();

    // Cleanup
    timeoutManager.close();
  }


}



