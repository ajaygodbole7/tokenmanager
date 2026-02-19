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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.Metrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * OAuth2TokenManager is responsible for managing the lifecycle of OAuth2 tokens including:
 * - Fetching and refreshing tokens from an OAuth2 server.
 * - Caching the token until near expiry.
 * - Providing concurrency control so that multiple callers do not trigger multiple simultaneous refreshes.
 * - Integrating with a circuit breaker and retry mechanism to handle transient failures.
 * - Handling various error conditions and mapping them to appropriate exceptions.
 *
 * Key Features:
 * 1. **Token Caching:** Keeps a valid token in memory and returns it until near expiry.
 * 2. **Concurrent Refresh Handling:** Ensures only one thread initiates a token refresh at a time. Other threads wait for the same refresh result.
 * 3. **Circuit Breaker & Retry:** Uses Resilience4j to handle transient failures and service disruptions.
 * 4. **Robust Response Validation:** Validates response fields, logs unexpected or malformed responses, and gracefully handles errors.
 *
 * Concurrency Design Notes:
 * - A ReentrantLock (`refreshLock`) is used to protect shared state related to ongoing refreshes.
 * - The lock is only held for short periods (checking and assigning `ongoingRefresh`) to reduce the risk of deadlocks.
 * - The actual completion handler for the refresh `CompletableFuture` acquires the lock again only briefly to update shared state.
 * - If multiple threads call `getToken()` and a refresh is needed, they all wait on the same future, ensuring that only one refresh is executed.
 */
@Slf4j
public final class OAuth2TokenManager implements TokenProvider {

  // Default configurations and constants
  private static final int FAILURE_THRESHOLD = 100;
  private static final int HALF_OPEN_CALLS = 1;
  private static final long MAX_EXPIRES_IN = 86400L * 365;
  private static final long MAX_RESPONSE_BODY_BYTES = 64 * 1024;

  private final TokenConfig config;
  private final Clock clock;
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final CircuitBreaker circuitBreaker;
  private final Retry retry;
  private final ExecutorService executor;
  private final String instanceId;
  /**
   * Lock to protect access to ongoingRefresh and other shared mutable states.
   * Concurrency Decision: Using a ReentrantLock provides a clear and explicit concurrency control mechanism.
   * This lock is only held for short durations when reading/writing the ongoingRefresh future.
   */
  private final ReentrantLock refreshLock = new ReentrantLock();
  // Current valid or soon-to-be-refreshed token
  private volatile OAuth2Token currentToken;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean refreshWarningLogged = new AtomicBoolean(false);
  /**
   * Represents the ongoing token refresh operation.
   * If null, no refresh is in progress. If non-null, all callers should wait on this future.
   */
  private CompletableFuture<OAuth2Token> ongoingRefresh;

  /**
   * Constructs an OAuth2TokenManager instance.
   *
   * @param config TokenConfig containing client credentials, endpoint, and other parameters.
   */
  public OAuth2TokenManager(@NonNull TokenConfig config) {
    config.validate();
    this.config = config;
    this.clock = config.getClock();

    this.instanceId = generateInstanceId(config.getClientId());
    this.httpClient = Optional.ofNullable(config.getHttpClient())
        .orElseGet(() -> createHttpClient(config.getHttpTimeout()));
    this.objectMapper = new ObjectMapper();
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    this.circuitBreaker = createCircuitBreaker();
    this.retry = createRetry();


    // null currentToken forces a refresh on first call
    this.currentToken = null;

    if (config.isEagerFetch()) {
      try {
        refreshToken().join();
      } catch (CompletionException ex) {
        close();
        Throwable cause = ex.getCause();
        if (cause instanceof TokenException te) throw te;
        throw new ServiceUnavailableException("Eager token fetch failed", cause);
      }
    }
  }

  /**
   * Creates an OkHttpClient with specified timeouts.
   *
   * @param timeout The timeout duration for all HTTP operations.
   * @return A configured OkHttpClient instance.
   */
  private static OkHttpClient createHttpClient(Duration timeout) {
    return new OkHttpClient.Builder()
        .connectTimeout(timeout)
        .readTimeout(timeout)
        .writeTimeout(timeout)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build();
  }

  /**
   * Generates an instance ID for the circuit breaker and retry from the given client ID.
   *
   * @param clientId The OAuth2 client ID
   * @return A sanitized instance ID string
   */
  private static String generateInstanceId(String clientId) {
    return "oauth2-token-manager-" + clientId;
  }

  /**
   * Returns a valid OAuth2 token to the caller. If the current token is close to expiry or expired:
   * - Initiates a refresh if none is ongoing.
   * - If a refresh is ongoing, waits for it to complete.
   *
   * @return A valid token as a String
   * @throws InvalidCredentialsException if the server indicates that credentials are invalid.
   * @throws InvalidEndpointException if the token endpoint is invalid or unreachable.
   * @throws ServiceUnavailableException if the service is down, circuit is open, or refresh timed out.
   */

  @Override
  public CompletableFuture<String> getTokenAsync() {
    if (closed.get()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("TokenManager is closed"));
    }

    String cachedToken = returnCachedTokenIfValid();
    if (cachedToken != null) {
      return CompletableFuture.completedFuture(cachedToken);
    }

    emitRefreshWarningIfNeeded();

    if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
      OAuth2Token token = currentToken;
      String expiryInfo = token != null ? "Current token expires at: " + token.expiresAt() : "No token available";
      ServiceUnavailableException cbException = new ServiceUnavailableException(
          "Service unavailable. " + expiryInfo);
      try {
        return CompletableFuture.completedFuture(tryGracefulDegradation(cbException));
      } catch (TokenException ex) {
        return CompletableFuture.failedFuture(ex);
      }
    }

    return refreshToken()
        .thenApply(OAuth2Token::tokenValue)
        .exceptionallyCompose(ex -> {
          TokenException mapped = mapToTokenException(ex);
          try {
            return CompletableFuture.completedFuture(tryGracefulDegradation(mapped));
          } catch (TokenException te) {
            return CompletableFuture.failedFuture(te);
          }
        });
  }

  @Override
  public void invalidate() {
    refreshLock.lock();
    try {
      currentToken = null;
    } finally {
      refreshLock.unlock();
    }
  }

  @Override
  public String getToken() {
    try {
      Duration overallTimeout = computeOverallTimeout();
      return getTokenAsync().get(overallTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof TokenException te) throw te;
      if (cause instanceof IllegalStateException ise) throw ise;
      throw new ServiceUnavailableException("Unexpected failure", cause);
    } catch (TimeoutException e) {
      throw new ServiceUnavailableException("Token refresh timed out", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceUnavailableException("Token refresh interrupted", e);
    } catch (CancellationException e) {
      throw new ServiceUnavailableException("Token refresh was canceled", e);
    }
  }

  /**
   * Checks if current token is still valid. If yes, returns it. Otherwise, returns null.
   */
  private String returnCachedTokenIfValid() {
    OAuth2Token token = currentToken;
    if (token != null && token.isValid(config.getRefreshThreshold(), clock)) {
      return token.tokenValue();
    }
    return null;
  }

  /**
   * Warns once when a single-exchange grant type attempts a second refresh.
   */
  private void emitRefreshWarningIfNeeded() {
    if (refreshWarningLogged.get()
        || currentToken == null
        || config.getGrantType() == OAuth2GrantType.CLIENT_CREDENTIALS
        || config.getGrantType() == OAuth2GrantType.JWT_BEARER) {
      return;
    }
    if (refreshWarningLogged.compareAndSet(false, true)) {
      log.warn("Grant type {} does not support automatic refresh. "
          + "First exchange succeeded but subsequent refreshes will replay "
          + "the original grant parameters and likely fail. "
          + "See README grant type caveats.", config.getGrantType());
    }
  }

  /**
   * Maps an exception from a CompletableFuture stage to the sealed TokenException hierarchy.
   * Unwraps CompletionException, then classifies: TokenException passthrough,
   * CancellationException, RuntimeException with UnknownHostException cause-chain walk,
   * generic ServiceUnavailableException fallback.
   */
  private TokenException mapToTokenException(Throwable ex) {
    Throwable cause = ex;
    if (cause instanceof CompletionException ce) {
      cause = ce.getCause();
    }
    if (cause instanceof TokenException te) {
      return te;
    }
    if (cause instanceof CancellationException ce) {
      return new ServiceUnavailableException("Token refresh was canceled", ce);
    }
    if (cause instanceof CallNotPermittedException cnpe) {
      return new ServiceUnavailableException("Circuit breaker is open", cnpe);
    }
    if (cause instanceof RuntimeException re) {
      for (Throwable t = re; t != null; t = t.getCause()) {
        if (t instanceof UnknownHostException || t instanceof SSLHandshakeException) {
          return new InvalidEndpointException("Token endpoint unreachable", t);
        }
      }
      return new ServiceUnavailableException("Unexpected runtime failure", re);
    }
    return new ServiceUnavailableException("Service is unavailable", cause);
  }

  /**
   * Attempts graceful degradation for transient failures. Returns the cached token
   * if it is still unexpired and the failure is transient (ServiceUnavailableException
   * or RateLimitedException). Rethrows permanent failures immediately.
   */
  private String tryGracefulDegradation(TokenException e) {
    OAuth2Token cached = currentToken;
    if (cached != null
        && (e instanceof ServiceUnavailableException || e instanceof RateLimitedException)
        && clock.instant().isBefore(cached.expiresAt())) {
      log.warn("Transient refresh failure for client {}. Returning current token (expires at {}): {}",
          config.getClientId(), cached.expiresAt(), e.getMessage());
      return cached.tokenValue();
    }
    throw e;
  }

  /**
   * Computes the maximum time a synchronous {@link #getToken()} can block, accounting for
   * the first HTTP attempt, all retry backoff intervals, and a safety buffer.
   *
   * <p><b>Formula:</b>
   * <pre>
   *   totalMs = httpTimeout                                      // first attempt
   *           + sum(i=1..maxAttempts-1) [ backoff(i) + httpTimeout ]  // retries
   *           + max(5000, maxAttempts * 1000)                    // safety buffer
   * </pre>
   *
   * <p>Each {@code backoff(i)} is {@code initialRetryDelay * 2^(i-1) * 1.5}, which represents
   * the worst-case delay when Resilience4j's exponential random backoff (randomization factor
   * 0.5) produces its maximum jitter. The multiplier 1.5 = 1.0 + 0.5 caps the jitter ceiling.
   *
   * <p>The safety buffer — {@code max(5000ms, maxAttempts * 1000ms)} — accounts for scheduling
   * variability in virtual threads, lock contention on {@code refreshLock}, and the inherent
   * randomness of jitter (the 1.5x ceiling is conservative but not exact). A floor of 5 seconds
   * prevents the buffer from collapsing when {@code maxAttempts} is small.
   *
   * <p><b>Example with defaults</b> (httpTimeout=10s, initialRetryDelay=1s, maxAttempts=3):
   * <pre>
   *   attempt 1: 10 000 ms
   *   retry  1:   1 500 ms backoff + 10 000 ms = 11 500 ms
   *   retry  2:   3 000 ms backoff + 10 000 ms = 13 000 ms
   *   buffer:     max(5000, 3000) = 5 000 ms
   *   total:      39 500 ms (~40s)
   * </pre>
   */
  private Duration computeOverallTimeout() {
    long httpMs = config.getHttpTimeout().toMillis();
    long retryDelayMs = config.getInitialRetryDelay().toMillis();
    int maxAttempts = config.getMaxRetryAttempts();

    long totalMs = httpMs; // first attempt
    for (int i = 1; i < maxAttempts; i++) {
      long backoff = (long) (retryDelayMs * Math.pow(2.0, i - 1) * 1.5);
      totalMs += backoff + httpMs;
    }
    return Duration.ofMillis(totalMs + Math.max(5000L, (long) maxAttempts * 1000L));
  }

  /**
   * Ensures that only one token refresh operation is triggered at a time:
   * - If a refresh is already ongoing, returns that future.
   * - Otherwise, starts a new refresh and stores it in ongoingRefresh.
   *
   * Concurrency Decision:
   * Lock is acquired only to read/modify ongoingRefresh and to set up a new refresh if needed.
   * After setting up the refresh future, we release the lock before attaching the completion handler,
   * minimizing time under lock and reducing risk of deadlocks.
   *
   * @return A CompletableFuture that will complete with a new OAuth2Token or an appropriate exception.
   */
  private CompletableFuture<OAuth2Token> refreshToken() {
    CompletableFuture<OAuth2Token> existingRefresh;
    CompletableFuture<OAuth2Token> newRefresh = null;

    refreshLock.lock();
    try {
      existingRefresh = ongoingRefresh;
      if (existingRefresh != null && !existingRefresh.isDone()) {
        // Another thread is currently refreshing, use that future
        return existingRefresh;
      }
      // Need to start a new refresh
      newRefresh = startNewRefresh();
      ongoingRefresh = newRefresh;
    } finally {
      refreshLock.unlock();
    }

    // Set up completion handler outside the lock
    // This avoids holding the lock while waiting for IO to complete.
    if (newRefresh != null) {
      CompletableFuture<OAuth2Token> finalNewRefresh = newRefresh;
      newRefresh.whenComplete((result, error) -> {
        // Acquire lock again only for a short time to update shared state
        refreshLock.lock();
        try {
          // Only update if ongoingRefresh still points to this future
          if (ongoingRefresh == finalNewRefresh) {
            if (error == null) {
              currentToken = result; // Successfully obtained a new token
            }
            ongoingRefresh = null;
          }
        } finally {
          refreshLock.unlock();
        }
      });
    }

    return newRefresh;
  }

  /**
   * Initiates a new token refresh operation asynchronously.
   * Decorates the supplier with retry and circuit breaker logic.
   *
   * The refresh is run in an ExecutorService's thread pool.
   *
   * @return A CompletableFuture containing the new OAuth2Token or throwing the appropriate exception on failure.
   */
  private CompletableFuture<OAuth2Token> startNewRefresh() {
    try {
      Supplier<OAuth2Token> supplier = () -> {
        try {
          return requestNewToken();
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      };
      supplier = Retry.decorateSupplier(retry, supplier);
      supplier = CircuitBreaker.decorateSupplier(circuitBreaker, supplier);
      return CompletableFuture.supplyAsync(supplier, executor);
    } catch (RejectedExecutionException e) {
      return CompletableFuture.failedFuture(
          new ServiceUnavailableException("Token manager is closed", e));
    }
  }

  /**
   * Makes a synchronous HTTP request to the OAuth2 server to obtain a new token.
   * Validates the response for correctness and throws exceptions for known error conditions.
   *
   * @return A new OAuth2Token retrieved from the server
   * @throws IOException If an IO error occurs while communicating with the server
   * @throws InvalidCredentialsException, InvalidEndpointException, ServiceUnavailableException on error responses
   */
  private OAuth2Token requestNewToken() throws IOException {
    FormBody.Builder formBuilder = new FormBody.Builder()
        .add("grant_type", config.getGrantType().toString());

    Request.Builder requestBuilder = new Request.Builder()
        .url(config.getTokenEndpoint());

    switch (config.getClientAuthMethod()) {
      case CLIENT_SECRET_POST -> {
        formBuilder.add("client_id", config.getClientId());
        formBuilder.add("client_secret", config.getClientSecret());
      }
      case CLIENT_SECRET_BASIC -> {
        requestBuilder.header("Authorization",
            Credentials.basic(config.getClientId(), config.getClientSecret(),
                StandardCharsets.UTF_8));
      }
    }

    addGrantTypeSpecificParams(formBuilder);

    if (!config.getScopeString().isEmpty()) {
      formBuilder.add("scope", config.getScopeString());
    }

    requestBuilder.post(formBuilder.build());

    try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
      if (!response.isSuccessful()) {
        handleErrorResponse(response);
      }

      return parseTokenResponse(response);
    }
  }

  /**
   * Adds parameters specific to the configured grant type to the request body.
   *
   * @param formBuilder The FormBody.Builder to add parameters to.
   */
  private void addGrantTypeSpecificParams(FormBody.Builder formBuilder) {
    switch (config.getGrantType()) {
      case PASSWORD -> {
        formBuilder.add("username", config.getUsername())
            .add("password", config.getPassword());
      }
      case AUTHORIZATION_CODE -> {
        formBuilder.add("code", config.getAuthorizationCode())
            .add("redirect_uri", config.getRedirectUri());
        if (config.getCodeVerifier() != null) {
          formBuilder.add("code_verifier", config.getCodeVerifier());
        }
      }
      case REFRESH_TOKEN -> {
        formBuilder.add("refresh_token", config.getRefreshToken());
      }
      case JWT_BEARER -> {
        formBuilder.add("assertion", config.getAssertion());
      }
      case CLIENT_CREDENTIALS -> { /* no additional params */ }
      case IMPLICIT -> throw new IllegalStateException("IMPLICIT rejected at validation");
    }
  }

  private void handleErrorResponse(Response response) throws IOException {
    // 429 classification is status-code authoritative — the response body
    // is irrelevant. This guarantees RateLimitedException regardless of
    // whether the server sends a JSON OAuth2 error or plain text.
    if (response.code() == 429) {
      throw buildRateLimitedException(response);
    }

    String errorBody = readErrorBodySafely(response);
    JsonNode errorNode = tryParseErrorBody(errorBody);
    if (errorNode != null && errorNode.has("error")) {
      classifyAndThrowOAuth2Error(response, errorNode, errorBody);
    } else {
      fallbackToHttpStatusHandling(response, errorBody);
    }
  }

  /**
   * Reads the error body from the response, or returns a default message if unavailable.
   */
  private String readErrorBodySafely(Response response) throws IOException {
    return response.body() != null ? readBodyWithLimit(response.body()) : "no error body";
  }

  /**
   * Attempts to parse the error response body as JSON.
   * Returns the parsed JsonNode, or null if parsing fails.
   */
  private JsonNode tryParseErrorBody(String errorBody) {
    try {
      return objectMapper.readTree(errorBody);
    } catch (Exception e) {
      log.error("Failed to parse error response as JSON for client {}", config.getClientId(), e);
      return null;
    }
  }

  /**
   * Classifies the OAuth2 error from the given errorNode and throws the appropriate exception.
   * Unknown error codes fall back to HTTP status classification so callers always see a
   * TokenException subclass, never an IllegalArgumentException.
   */
  private void classifyAndThrowOAuth2Error(Response response, JsonNode errorNode, String errorBody) {
    String rawError = errorNode.get("error").asText();
    OAuth2ErrorCode errorCode = OAuth2ErrorCode.fromString(rawError);

    if (errorCode == null) {
      log.warn("Unknown OAuth2 error code '{}' from server, falling back to HTTP status classification", rawError);
      fallbackToHttpStatusHandling(response, errorBody);
      return; // unreachable — fallback always throws
    }

    String errorDescription = errorNode.has("error_description")
        ? errorNode.get("error_description").asText()
        : errorCode.toString();

    throw switch (errorCode) {
      case INVALID_CLIENT, INVALID_GRANT, UNAUTHORIZED_CLIENT,
           ACCESS_DENIED, INVALID_TOKEN ->
          new InvalidCredentialsException(errorDescription);
      case INVALID_REQUEST, INVALID_SCOPE, UNSUPPORTED_GRANT_TYPE,
           INSUFFICIENT_SCOPE, INVALID_REDIRECT_URI,
           UNSUPPORTED_RESPONSE_TYPE, UNSUPPORTED_TOKEN_TYPE ->
          new InvalidConfigurationException(errorDescription);
      case SERVER_ERROR, TEMPORARILY_UNAVAILABLE ->
          new ServiceUnavailableException(errorDescription);
    };
  }

  /**
   * If JSON parsing fails or no known error code was found, fallback to HTTP status handling.
   */
  private void fallbackToHttpStatusHandling(Response response, String errorBody) {
    if (response.code() == 401 || response.code() == 403) {
      throw new InvalidCredentialsException("Authentication failed");
    } else if (response.code() >= 500) {
      throw new ServiceUnavailableException("Server error: " + response.code());
    } else {
      throw new InvalidEndpointException("Invalid request: " + response.code());
    }
  }

  /**
   * Builds a RateLimitedException from a 429 response, parsing the Retry-After
   * header into a Duration when present.
   */
  private RateLimitedException buildRateLimitedException(Response response) {
    String retryAfterHeader = response.header("Retry-After");
    Duration retryAfter = parseRetryAfter(retryAfterHeader);
    String msg = retryAfter != null
        ? "Rate limited by server. Retry after " + retryAfter.getSeconds() + " seconds"
        : "Rate limited by server";
    return new RateLimitedException(msg, retryAfter);
  }

  /**
   * Parses a Retry-After header value into a Duration. Accepts either
   * delay-seconds (e.g. "120") or an HTTP-date in IMF-fixdate format
   * (e.g. "Fri, 31 Dec 1999 23:59:59 GMT") per RFC 7231 §7.1.3.
   * Returns null if the header is absent or unparseable.
   */
  private Duration parseRetryAfter(String retryAfterHeader) {
    if (retryAfterHeader == null) {
      return null;
    }
    String trimmed = retryAfterHeader.trim();
    try {
      return Duration.ofSeconds(Long.parseLong(trimmed));
    } catch (NumberFormatException e) {
      // Not an integer — try HTTP-date
    }
    try {
      Instant retryAt = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
          .parse(trimmed, Instant::from);
      Duration delta = Duration.between(clock.instant(), retryAt);
      return delta.isNegative() ? Duration.ZERO : delta;
    } catch (java.time.format.DateTimeParseException e) {
      log.warn("Unparseable Retry-After header: '{}'", trimmed);
      return null;
    }
  }

  /**
   * Parses the successful token response and performs robust validation.
   *
   * @throws IOException if an error occurs reading the response
   * @throws ServiceUnavailableException if response is malformed or missing required fields
   */
  private OAuth2Token parseTokenResponse(Response response) throws IOException {
    String responseBody = readResponseBodySafely(response);
    JsonNode node = parseResponseBodyAsJson(responseBody);
    validateTokenFields(node);
    return createOAuth2TokenFromNode(node);
  }

  /**
   * Reads the response body safely or returns an empty string if none.
   */
  private String readResponseBodySafely(Response response) throws IOException {
    return response.body() == null ? "" : readBodyWithLimit(response.body());
  }

  /**
   * Reads at most {@link #MAX_RESPONSE_BODY_BYTES} from the given response body.
   * Prevents out-of-memory conditions from malicious or misconfigured servers.
   */
  private String readBodyWithLimit(ResponseBody body) throws IOException {
    try (BufferedSource source = body.source()) {
      source.request(MAX_RESPONSE_BODY_BYTES);
      long available = source.getBuffer().size();
      if (available > MAX_RESPONSE_BODY_BYTES) {
        log.warn("Response body exceeded {} bytes, truncated", MAX_RESPONSE_BODY_BYTES);
      }
      return source.getBuffer()
          .readString(Math.min(available, MAX_RESPONSE_BODY_BYTES), StandardCharsets.UTF_8);
    }
  }

  /**
   * Parses the given responseBody as JSON.
   * Throws ServiceUnavailableException if parsing fails.
   */
  private JsonNode parseResponseBodyAsJson(String responseBody) {
    if (responseBody.isBlank()) {
      log.error("Empty response body from OAuth2 server");
      throw new ServiceUnavailableException("Empty response body from server");
    }
    try {
      return objectMapper.readTree(responseBody);
    } catch (IOException e) {
      log.error("Failed to parse token response as JSON for client {}", config.getClientId(), e);
      throw new ServiceUnavailableException("Malformed JSON response", e);
    }
  }


  /**
   * Validates that required fields (access_token, expires_in) are present and valid.
   */
  private void validateTokenFields(JsonNode node) {
    JsonNode accessTokenNode = node.get("access_token");
    if (accessTokenNode == null || accessTokenNode.asText().isBlank()) {
      log.error("Token response missing 'access_token' for client {}", config.getClientId());
      throw new ServiceUnavailableException("Missing access_token in response");
    }

    JsonNode expiresInNode = node.get("expires_in");
    if (expiresInNode == null || !expiresInNode.canConvertToLong()) {
      log.error("Token response missing or invalid 'expires_in' for client {}", config.getClientId());
      throw new ServiceUnavailableException("Missing or invalid expires_in in response");
    }

    long expiresIn = expiresInNode.asLong();
    if (expiresIn <= 0) {
      log.error("Token response has non-positive 'expires_in' ({}) for client {}",
          expiresIn, config.getClientId());
      throw new ServiceUnavailableException(
          "Invalid expires_in value: " + expiresIn + " (must be positive)");
    }
    if (expiresIn > MAX_EXPIRES_IN) {
      log.error("Token response has unreasonably large 'expires_in' ({}) for client {}",
          expiresIn, config.getClientId());
      throw new ServiceUnavailableException(
          "Invalid expires_in value: " + expiresIn + " (exceeds maximum of " + MAX_EXPIRES_IN + ")");
    }
  }

  /**
   * Creates an OAuth2Token from the validated JsonNode.
   */
  private OAuth2Token createOAuth2TokenFromNode(JsonNode node) {
    String accessToken = node.get("access_token").asText();
    long expiresIn = node.get("expires_in").asLong();

    JsonNode tokenTypeNode = node.get("token_type");
    OAuth2TokenType tokenType = (tokenTypeNode != null && !tokenTypeNode.asText().isBlank())
        ? OAuth2TokenType.fromString(tokenTypeNode.asText())
        : OAuth2TokenType.BEARER;

    Set<String> scopes = Set.of();
    JsonNode scopeNode = node.get("scope");
    if (scopeNode != null && !scopeNode.asText().isBlank()) {
      scopes = Set.of(scopeNode.asText().split("\\s+"));
    }

    Instant now = clock.instant();
    return new OAuth2Token(
        accessToken,
        tokenType,
        now,
        now.plusSeconds(expiresIn),
        scopes
    );
  }

  /**
   * Creates a CircuitBreaker with the configured thresholds and durations.
   *
   * @return A CircuitBreaker instance.
   */
  private CircuitBreaker createCircuitBreaker() {
    var cbConfig =
        CircuitBreakerConfig.custom()
            .failureRateThreshold(FAILURE_THRESHOLD)
            .minimumNumberOfCalls(config.getCircuitBreakerMinimumCalls())
            .waitDurationInOpenState(config.getCircuitBreakerWaitDuration())
            .permittedNumberOfCallsInHalfOpenState(HALF_OPEN_CALLS)
            .recordException(e -> {
              if (e instanceof ServiceUnavailableException) return true;
              if (e instanceof UncheckedIOException) {
                for (Throwable t = e; t != null; t = t.getCause()) {
                  if (t instanceof UnknownHostException || t instanceof SSLHandshakeException) {
                    return false;
                  }
                }
                return true;
              }
              return false;
            })
            .ignoreExceptions(RateLimitedException.class)
            .build();

    CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(cbConfig);
    CircuitBreaker cb = registry.circuitBreaker(instanceId);

    cb.getEventPublisher().onStateTransition(this::logCircuitBreakerStateTransition);
    return cb;
  }

  /**
   * Creates a Retry instance with exponential backoff for transient failures.
   *
   * @return A Retry instance.
   */
  private Retry createRetry() {
    IntervalFunction intervalFunction =
        IntervalFunction.ofExponentialRandomBackoff(
            config.getInitialRetryDelay().toMillis(), 2.0, 0.5);

    var retryConfig =
        RetryConfig.<OAuth2Token>custom()
            .maxAttempts(config.getMaxRetryAttempts())
            .intervalFunction(intervalFunction)
            .retryOnException(e -> {
              if (e instanceof UncheckedIOException) {
                for (Throwable t = e; t != null; t = t.getCause()) {
                  if (t instanceof UnknownHostException || t instanceof SSLHandshakeException) {
                    return false;
                  }
                }
                return true;
              }
              return false;
            })
            .build();

    RetryRegistry registry = RetryRegistry.of(retryConfig);
    Retry retry = registry.retry(instanceId);

    retry.getEventPublisher()
        .onRetry(event -> log.info("Retrying token refresh after failure: {}", event))
        .onError(event -> log.error("Retry failed: {}", event));

    return retry;
  }

  /**
   * Logs CircuitBreaker state transitions for debugging and operational visibility.
   *
   * @param event The state transition event.
   */
  private void logCircuitBreakerStateTransition(CircuitBreakerOnStateTransitionEvent event) {
    State fromState = event.getStateTransition().getFromState();
    State toState = event.getStateTransition().getToState();

    String transitionMessage =
        switch (toState) {
          case OPEN -> {
              OAuth2Token token = currentToken;
              String expiryInfo = token != null ? "Last token expires at: " + token.expiresAt() : "No token available";
              yield String.format(
                  "Service protection activated for client %s. "
                      + "Token refresh suspended for %d seconds after reaching %d%% failure rate. "
                      + "%s",
                  config.getClientId(),
                  config.getCircuitBreakerWaitDuration().toSeconds(),
                  FAILURE_THRESHOLD,
                  expiryInfo);
          }
          case HALF_OPEN ->
              String.format(
                  "Testing service availability for client %s. "
                      + "Will attempt %d test request(s) to verify stability",
                  config.getClientId(), HALF_OPEN_CALLS);
          case CLOSED ->
              String.format(
                  "Service restored for client %s. Normal token refresh operations resumed",
                  config.getClientId());
          case DISABLED ->
              String.format(
                  "Circuit breaker disabled for client %s. This is unexpected in normal operation",
                  config.getClientId());
          case FORCED_OPEN ->
              String.format(
                  "Circuit breaker manually opened for client %s. Token refresh suspended",
                  config.getClientId());
          case METRICS_ONLY -> null;
        };

    if (transitionMessage != null) {
      switch (toState) {
        case OPEN -> log.error(transitionMessage);
        case HALF_OPEN -> log.warn(transitionMessage);
        case CLOSED -> log.info(transitionMessage);
        default -> log.warn(transitionMessage);
      }
    }

    try {
      Metrics metrics = circuitBreaker.getMetrics();
      log.debug(
          "Circuit breaker metrics for client {}: Failure rate={}%, Slow call rate={}%, "
              + "Number of failed calls={}, Number of slow calls={}, Number of not permitted calls={}",
          config.getClientId(),
          metrics.getFailureRate(),
          metrics.getSlowCallRate(),
          metrics.getNumberOfFailedCalls(),
          metrics.getNumberOfSlowCalls(),
          metrics.getNumberOfNotPermittedCalls());
    } catch (Exception ignored) {
      log.debug("Could not retrieve circuit breaker metrics for client {}", config.getClientId());
    }
  }

  /**
   * Closes the Token Manager, releasing resources and cancelling any ongoing refresh.
   * This ensures a clean shutdown scenario.
   *
   * <p>If an HTTP request is in-flight when close() is called, this method may block
   * until the request completes or the configured httpTimeout fires, plus up to
   * 5 additional seconds for executor termination.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    // Cancel any ongoing refresh operation safely under the lock
    refreshLock.lock();
    try {
      if (ongoingRefresh != null) {
        ongoingRefresh.cancel(true);
        ongoingRefresh = null;
      }
    } finally {
      refreshLock.unlock();
    }

    // Shutdown executor gracefully
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
          log.error("Executor did not terminate for client {}", config.getClientId());
        }
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }

    // Close HTTP resources only if we created the client
    if (httpClient != null && config.getHttpClient() == null) {
      httpClient.dispatcher().executorService().shutdown();
      httpClient.connectionPool().evictAll();
    }
  }

  /** Package-private accessor for testing the one-time refresh warning. */
  boolean isRefreshWarningLogged() {
    return refreshWarningLogged.get();
  }

}
