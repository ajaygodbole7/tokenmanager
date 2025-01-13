package org.tokenmanager;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@DisplayName("OAuth2TokenManager4 Integration Tests")
class OAuth2TokenManager4Test {
  /*

  @RegisterExtension
  static WireMockExtension wireMock = WireMockExtension.newInstance()
      .options(WireMockConfiguration.wireMockConfig()
                   .dynamicPort()
                   .dynamicHttpsPort()
                   .httpDisabled(true)    // Disable HTTP since we're using HTTPS
                   .keystorePath("src/test/resources/test-keystore.jks")  // You'll need to create this
                   .keystorePassword("password"))
      .build();

  private Oauth2TokenManager4 tokenManager;
  private TokenConfig config;
  private ExecutorService testExecutor;

  @BeforeEach
  void setUp() {
    String tokenEndpoint = String.format("http://localhost:%d/oauth/token",
                                         wireMock.getPort());

    config = TokenConfig.builder()
        .clientId("test-client")
        .clientSecret("test-secret")
        .tokenEndpoint(tokenEndpoint)
        .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
        .httpTimeout(Duration.ofSeconds(5))
        .refreshThreshold(Duration.ofSeconds(30))
        .build();

    tokenManager = new Oauth2TokenManager4(config);
    testExecutor = Executors.newFixedThreadPool(10);

    wireMock.resetAll();
  }

  @AfterEach
  void tearDown() {
    testExecutor.shutdownNow();
    tokenManager.close();
  }

  @Nested
  @DisplayName("Token Retrieval Tests")
  class TokenRetrievalTests {

    @Test
    @DisplayName("Should retrieve and cache valid token")
    void shouldRetrieveAndCacheValidToken() {
      // Given
      stubSuccessfulTokenResponse("valid-token");

      // When
      String firstToken = tokenManager.getToken();
      String secondToken = tokenManager.getToken();

      // Then
      assertThat(firstToken)
          .isEqualTo(secondToken)
          .isEqualTo("valid-token");

      wireMock.verify(1, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    @Test
    @DisplayName("Should refresh expired token")
    void shouldRefreshExpiredToken() throws Exception {
      // Given
      stubSuccessfulTokenResponse("token-1");
      String firstToken = tokenManager.getToken();

      // Simulate token expiration
      Thread.sleep(Duration.ofSeconds(31).toMillis());

      stubSuccessfulTokenResponse("token-2");

      // When
      String secondToken = tokenManager.getToken();

      // Then
      assertThat(secondToken)
          .isNotEqualTo(firstToken)
          .isEqualTo("token-2");

      wireMock.verify(2, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    @Test
    @DisplayName("Should validate token response fields")
    void shouldValidateTokenResponseFields() {
      // Given
      stubInvalidTokenResponse();

      // When/Then
      assertThatThrownBy(() -> tokenManager.getToken())
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessageContaining("Missing access_token in response");
    }
  }
  @Nested
  @DisplayName("Concurrent Access Tests")
  class ConcurrentAccessTests {

    @Test
    @DisplayName("Should handle multiple concurrent requests")
    void shouldHandleConcurrentRequests() throws Exception {
      // Given
      stubSuccessfulTokenResponse("concurrent-token");
      int numThreads = 10;
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(numThreads);
      List<String> results = new CopyOnWriteArrayList<>();

      // When
      for (int i = 0; i < numThreads; i++) {
        testExecutor.submit(() -> {
          try {
            startLatch.await();
            results.add(tokenManager.getToken());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          } finally {
            completionLatch.countDown();
          }
        });
      }

      startLatch.countDown();
      boolean completed = completionLatch.await(5, TimeUnit.SECONDS);

      // Then
      assertThat(completed).isTrue();
      assertThat(results)
          .hasSize(numThreads)
          .containsOnly("concurrent-token");

      wireMock.verify(1, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    @Test
    @DisplayName("Should handle concurrent refresh requests")
    void shouldHandleConcurrentRefreshRequests() throws Exception {
      // Given
      stubSuccessfulTokenResponse("token-1");
      tokenManager.getToken();
      Thread.sleep(Duration.ofSeconds(31).toMillis()); // Expire token

      // Add 500ms delay to increase chance of concurrent requests
      stubSuccessfulTokenResponse("refreshed-token", 500);

      int numThreads = 10;
      CountDownLatch startLatch = new CountDownLatch(1);
      List<CompletableFuture<String>> futures = new ArrayList<>();

      // When
      for (int i = 0; i < numThreads; i++) {
        futures.add(CompletableFuture.supplyAsync(() -> {
          try {
            startLatch.await();
            return tokenManager.getToken();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
          }
        }, testExecutor));
      }

      startLatch.countDown();
      List<String> results = futures.stream()
          .map(CompletableFuture::join)
          .collect(Collectors.toList());

      // Then
      assertThat(results)
          .hasSize(numThreads)
          .containsOnly("refreshed-token");

      wireMock.verify(2, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    @Test
    @DisplayName("Should handle refresh failure during concurrent requests")
    void shouldHandleRefreshFailureDuringConcurrentRequests() throws Exception {
      // Given
      stubSuccessfulTokenResponse("token-1");
      tokenManager.getToken();
      Thread.sleep(Duration.ofSeconds(31).toMillis()); // Expire token

      stubErrorResponse(500, "server_error", "Internal server error");

      int numThreads = 5;
      CountDownLatch startLatch = new CountDownLatch(1);
      List<CompletableFuture<String>> futures = new ArrayList<>();

      // When
      for (int i = 0; i < numThreads; i++) {
        futures.add(CompletableFuture.supplyAsync(() -> {
          try {
            startLatch.await();
            return tokenManager.getToken();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
          }
        }, testExecutor));
      }

      startLatch.countDown();

      // Then
      assertThatThrownBy(() ->
                             futures.stream()
                                 .map(CompletableFuture::join)
                                 .collect(Collectors.toList())
      )
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(ServiceUnavailableException.class);
    }
  }

  @Nested
  @DisplayName("Error Handling Tests")
  class ErrorHandlingTests {

    @Test
    @DisplayName("Should handle invalid credentials")
    void shouldHandleInvalidCredentials() {
      // Given
      stubErrorResponse(401, "invalid_client", "Invalid client credentials");

      // When/Then
      assertThatThrownBy(() -> tokenManager.getToken())
          .isInstanceOf(InvalidCredentialsException.class)
          .hasMessageContaining("Invalid client credentials");
    }

    @Test
    @DisplayName("Should handle server errors")
    void shouldHandleServerErrors() {
      // Given
      stubErrorResponse(500, "server_error", "Internal server error");

      // When/Then
      assertThatThrownBy(() -> tokenManager.getToken())
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessageContaining("Internal server error");
    }

    @Test
    @DisplayName("Should handle network timeout")
    void shouldHandleTimeout() {
      // Given
      stubFor(post(urlEqualTo("/oauth/token"))
                  .willReturn(aResponse()
                                  .withFixedDelay(6000))); // Longer than client timeout

      // When/Then
      assertThatThrownBy(() -> tokenManager.getToken())
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessageContaining("timed out");
    }

    @Test
    @DisplayName("Should handle malformed JSON response")
    void shouldHandleMalformedJsonResponse() {
      // Given
      wireMock.stubFor(post(urlEqualTo("/oauth/token"))
                           .willReturn(aResponse()
                                           .withHeader("Content-Type", "application/json")
                                           .withBody("invalid json{}")));

      // When/Then
      assertThatThrownBy(() -> tokenManager.getToken())
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessageContaining("Malformed JSON response");
    }
  }

  @Nested
  @DisplayName("Circuit Breaker Tests")
  class CircuitBreakerTests {

    @Test
    @DisplayName("Should open circuit after consecutive failures")
    void shouldOpenCircuitAfterFailures() {
      // Given
      stubErrorResponse(500, "server_error", "Internal server error");

      // When
      for (int i = 0; i < 5; i++) {
        assertThatThrownBy(() -> tokenManager.getToken())
            .isInstanceOf(ServiceUnavailableException.class);
      }

      // Then - Circuit should be open now
      assertThatThrownBy(() -> tokenManager.getToken())
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessageContaining("circuit breaker is open");

      wireMock.verify(5, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    @Test
    @DisplayName("Should attempt half-open state after wait duration")
    void shouldAttemptHalfOpenState() throws Exception {
      // Given - Get circuit breaker to open state
      stubErrorResponse(500, "server_error", "Internal server error");
      for (int i = 0; i < 5; i++) {
        assertThatThrownBy(() -> tokenManager.getToken())
            .isInstanceOf(ServiceUnavailableException.class);
      }

      // When - Wait for circuit breaker timeout and stub successful response
      Thread.sleep(Duration.ofSeconds(60).toMillis());
      stubSuccessfulTokenResponse("recovery-token");

      // Then - Should recover and get new token
      String token = tokenManager.getToken();
      assertThat(token).isEqualTo("recovery-token");
    }

    @Test
    @DisplayName("Should remain open after failed recovery attempt")
    void shouldRemainOpenAfterFailedRecovery() throws Exception {
      // Given - Get circuit breaker to open state
      stubErrorResponse(500, "server_error", "Internal server error");
      for (int i = 0; i < 5; i++) {
        assertThatThrownBy(() -> tokenManager.getToken())
            .isInstanceOf(ServiceUnavailableException.class);
      }

      // When - Wait and attempt recovery with error
      Thread.sleep(Duration.ofSeconds(60).toMillis());
      stubErrorResponse(500, "server_error", "Still failing");

      // Then - Should remain open after failed recovery
      assertThatThrownBy(() -> tokenManager.getToken())
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessageContaining("circuit breaker is open");
    }
  }

  // Helper Methods
  private void stubSuccessfulTokenResponse(String tokenValue, int delayMillis) {
    wireMock.stubFor(post(urlEqualTo("/oauth/token"))
                         .willReturn(aResponse()
                                         .withHeader("Content-Type", "application/json")
                                         .withFixedDelay(delayMillis)
                                         .withBody(String.format("""
                    {
                        "access_token": "%s",
                        "token_type": "Bearer",
                        "expires_in": 3600,
                        "scope": "read write"
                    }
                    """, tokenValue))));
  }

  private void stubSuccessfulTokenResponse(String tokenValue) {
    wireMock.stubFor(post(urlEqualTo("/oauth/token"))
                         .willReturn(aResponse()
                                         .withHeader("Content-Type", "application/json")
                                         .withBody(String.format("""
                {
                    "access_token": "%s",
                    "token_type": "Bearer",
                    "expires_in": 3600
                }
                """, tokenValue))));
  }

  private void stubErrorResponse(int status, String error, String description) {
    wireMock.stubFor(post(urlEqualTo("/oauth/token"))
                         .willReturn(aResponse()
                                         .withStatus(status)
                                         .withHeader("Content-Type", "application/json")
                                         .withBody(String.format("""
                {
                    "error": "%s",
                    "error_description": "%s"
                }
                """, error, description))));
  }

  private void stubInvalidTokenResponse() {
    wireMock.stubFor(post(urlEqualTo("/oauth/token"))
                         .willReturn(aResponse()
                                         .withHeader("Content-Type", "application/json")
                                         .withBody("""
                    {
                        "token_type": "Bearer",
                        "expires_in": 3600,
                        "scope": "read write"
                    }
                    """)));
  }

   */
}
