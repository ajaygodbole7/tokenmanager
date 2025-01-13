package org.tokenmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.Metrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Oauth2TokenManager is responsible for managing the lifecycle of OAuth2 tokens including:
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
public class Oauth2TokenManager implements AutoCloseable {

  // Default configurations and constants
  private static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_REFRESH_THRESHOLD = Duration.ofSeconds(30);
  private static final int MAX_RETRY_ATTEMPTS = 3;
  private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);
  private static final int FAILURE_THRESHOLD = 100;
  private static final int MINIMUM_CALLS = 3;
  private static final Duration WAIT_DURATION = Duration.ofSeconds(60);
  private static final int HALF_OPEN_CALLS = 1;

  private final TokenConfig config;
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
  /**
   * Represents the ongoing token refresh operation.
   * If null, no refresh is in progress. If non-null, all callers should wait on this future.
   */
  private CompletableFuture<OAuth2Token> ongoingRefresh;

  /**
   * Constructs an Oauth2TokenManager instance.
   *
   * @param config TokenConfig containing client credentials, endpoint, and other parameters.
   */
  public Oauth2TokenManager(@NonNull TokenConfig config) {
    config.validate();
    this.config = config;

    this.instanceId = generateInstanceId(config.getClientId());
    //this.httpClient = createHttpClient(config.getHttpTimeout());
    this.httpClient = Optional.ofNullable(config.getHttpClient())
        .orElseGet(() -> createHttpClient(config.getHttpTimeout()));
    this.objectMapper = new ObjectMapper();
    this.executor = Executors.newCachedThreadPool();
    this.circuitBreaker = createCircuitBreaker();
    this.retry = createRetry();


    // Initialize with an invalid token to force a refresh on first call
    this.currentToken = OAuth2Token.invalidToken();
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
        .build();
  }

  /**
   * Generates an instance ID for the circuit breaker and retry from the given client ID.
   *
   * @param clientId The OAuth2 client ID
   * @return A sanitized instance ID string
   */
  private static String generateInstanceId(String clientId) {
    return String.format("oauth2-token-manager-%s", clientId.replaceAll("[^a-zA-Z0-9]", ""));
  }

  public static OAuth2Token invalidToken() {
    return new OAuth2Token("INVALID", OAuth2TokenType.BEARER, Instant.EPOCH, Instant.EPOCH, Set.of());
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

  public String getToken() {
    // Step 1: Possibly return cached token
    String cachedToken = returnCachedTokenIfValid();
    if (cachedToken != null) {
      return cachedToken;
    }

    // Step 2: Check circuit breaker state now that we know token is not valid
    checkCircuitBreaker();

    // Step 3: Proceed with refresh
    CompletableFuture<OAuth2Token> refreshOperation = refreshToken();

    // Step 4: Wait for refresh and handle exceptions
    return awaitRefreshAndHandleExceptions(refreshOperation);
  }

  /**
   * Checks if current token is still valid. If yes, returns it. Otherwise, returns null.
   */
  private String returnCachedTokenIfValid() {
    if (currentToken.isValid(config.getRefreshThreshold())) {
      return currentToken.tokenValue();
    }
    return null;
  }

  /**
   * Checks the circuit breaker state to determine if we can attempt a refresh.
   * Throws an exception if the circuit breaker is open and no valid token is present.
   */
  private void checkCircuitBreaker() {
    if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
      throw new ServiceUnavailableException(
          String.format("Service unavailable. Current token expires at: %s", currentToken.expiresAt()));
    }
  }

  /**
   * Awaits the completion of the refresh operation and maps any exceptions to appropriate runtime exceptions.
   */
  private String awaitRefreshAndHandleExceptions(CompletableFuture<OAuth2Token> refreshOperation) {
    try {
      OAuth2Token newToken = refreshOperation.get(config.getHttpTimeout().toMillis(), TimeUnit.MILLISECONDS);
      return newToken.tokenValue();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (isCredentialsError(cause)) {
        throw new InvalidCredentialsException("Invalid client credentials", cause);
      } else if (isEndpointError(cause)) {
        throw new InvalidEndpointException("Invalid OAuth2 endpoint", cause);
      }
      throw new ServiceUnavailableException("Service is unavailable", cause);
    } catch (TimeoutException e) {
      refreshOperation.cancel(true);
      throw new ServiceUnavailableException("Token refresh timed out", e);
    } catch (InterruptedException e) {
      refreshOperation.cancel(true);
      Thread.currentThread().interrupt();
      throw new ServiceUnavailableException("Token refresh interrupted", e);
    } catch (CancellationException e) {
      throw new ServiceUnavailableException("Token refresh was canceled", e);
    }
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

    return newRefresh != null ? newRefresh : existingRefresh;
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
    return CompletableFuture.supplyAsync(() -> {
      return Decorators.ofSupplier(() -> {
            try {
              return requestNewToken();
            } catch (IOException e) {
              throw new ServiceUnavailableException("Failed to refresh token", e);
            }
          })
          .withRetry(retry)
          .withCircuitBreaker(circuitBreaker)
          .decorate()
          .get();
    }, executor);
  }

  /**
   * Handles non-2xx responses by parsing the error and throwing the appropriate exception.
   * Falls back to HTTP status code if parsing fails.
   *
   * @param response The HTTP Response from the server
   * @throws IOException if reading the response body fails
   */

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
        .add("grant_type", config.getGrantType().toString())
        .add("client_id", config.getClientId())
        .add("client_secret", config.getClientSecret());

    addGrantTypeSpecificParams(formBuilder);

    if (!config.getScopeString().isEmpty()) {
      formBuilder.add("scope", config.getScopeString());
    }

    Request request = new Request.Builder()
        .url(config.getTokenEndpoint())
        .post(formBuilder.build())
        .build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
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
    }
  }

  /**
   * Parses the successful token response and performs robust validation:
   * - Checks for empty body.
   * - Ensures `access_token` and `expires_in` fields are present and valid.
   *
   * @param response The response from the OAuth2 token endpoint
   * @return A valid OAuth2Token object
   * @throws IOException If an error occurs reading the response
   * @throws ServiceUnavailableException If response is malformed or missing required fields
   */



  // New Code
  private void handleErrorResponse(Response response) throws IOException {
    String errorBody = readErrorBodySafely(response);
    JsonNode errorNode = tryParseErrorBody(errorBody);
    if (errorNode != null && errorNode.has("error")) {
      classifyAndThrowOAuth2Error(errorNode, errorBody);
    } else {
      fallbackToHttpStatusHandling(response, errorBody);
    }
  }

  /**
   * Reads the error body from the response, or returns a default message if unavailable.
   */
  private String readErrorBodySafely(Response response) throws IOException {
    return response.body() != null ? response.body().string() : "no error body";
  }

  /**
   * Attempts to parse the error response body as JSON.
   * Returns the parsed JsonNode, or null if parsing fails.
   */
  private JsonNode tryParseErrorBody(String errorBody) {
    try {
      return objectMapper.readTree(errorBody);
    } catch (Exception e) {
      log.error("Failed to parse error response: {}", errorBody, e);
      return null;
    }
  }

  /**
   * Classifies the OAuth2 error from the given errorNode and throws the appropriate exception.
   */
  private void classifyAndThrowOAuth2Error(JsonNode errorNode, String errorBody) {
    OAuth2ErrorCode errorCode = OAuth2ErrorCode.fromString(errorNode.get("error").asText());
    String errorDescription = errorNode.has("error_description")
        ? errorNode.get("error_description").asText()
        : errorCode.toString();

    switch (errorCode) {
      case INVALID_CLIENT, INVALID_GRANT ->
          throw new InvalidCredentialsException(errorDescription);
      case INVALID_REQUEST, INVALID_SCOPE, UNSUPPORTED_GRANT_TYPE ->
          throw new InvalidConfigurationException(errorDescription);
      case SERVER_ERROR, TEMPORARILY_UNAVAILABLE ->
          throw new ServiceUnavailableException(errorDescription);
      default ->
          throw new ServiceUnavailableException("OAuth2 error: " + errorDescription);
    }
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

  private OAuth2Token parseTokenResponse(Response response) throws IOException {
    String responseBody = readResponseBodySafely(response);
    JsonNode node = parseResponseBodyAsJson(responseBody);
    validateTokenFields(node, responseBody);
    return createOAuth2TokenFromNode(node);
  }

  /**
   * Reads the response body safely or returns an empty string if none.
   */
  private String readResponseBodySafely(Response response) throws IOException {
    return response.body() == null ? "" : response.body().string();
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
      log.error("Failed to parse token response as JSON: {}", responseBody, e);
      throw new ServiceUnavailableException("Malformed JSON response", e);
    }
  }


  //End New Code

  /**
   * Validates that required fields (access_token, expires_in) are present and valid.
   */
  private void validateTokenFields(JsonNode node, String responseBody) {
    JsonNode accessTokenNode = node.get("access_token");
    if (accessTokenNode == null || accessTokenNode.asText().isBlank()) {
      log.error("Response missing 'access_token': {}", responseBody);
      throw new ServiceUnavailableException("Missing access_token in response");
    }

    JsonNode expiresInNode = node.get("expires_in");
    if (expiresInNode == null || !expiresInNode.canConvertToLong()) {
      log.error("Response missing or invalid 'expires_in': {}", responseBody);
      throw new ServiceUnavailableException("Missing or invalid expires_in in response");
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

    Instant now = Instant.now();
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
            .minimumNumberOfCalls(MINIMUM_CALLS)
            .waitDurationInOpenState(WAIT_DURATION)
            .permittedNumberOfCallsInHalfOpenState(HALF_OPEN_CALLS)
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
        IntervalFunction.ofExponentialBackoff(INITIAL_RETRY_DELAY.toMillis());

    var retryConfig =
        RetryConfig.<TokenInfo>custom()
            .maxAttempts(MAX_RETRY_ATTEMPTS)
            .intervalFunction(intervalFunction)
            .retryOnException(e -> e instanceof IOException || e instanceof TimeoutException)
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
          case OPEN ->
              String.format(
                  "Service protection activated for client %s. "
                      + "Token refresh suspended for %d seconds after reaching %d%% failure rate. "
                      + "Last token expires at: %s",
                  config.getClientId(),
                  WAIT_DURATION.toSeconds(),
                  FAILURE_THRESHOLD,
                  currentToken.expiresAt());
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
    } catch (Exception e) {
      log.debug("Could not retrieve circuit breaker metrics for client {}", config.getClientId());
    }
  }

  /**
   * Checks if the given error indicates a credential problem.
   *
   * @param error The throwable to check
   * @return true if error is credential-related, false otherwise.
   */
  private boolean isCredentialsError(Throwable error) {
    return error instanceof InvalidCredentialsException ||
        (error.getMessage() != null && (error.getMessage().contains("401") || error.getMessage().contains("403")));
  }

  /**
   * Checks if the given error indicates an endpoint (network or configuration) problem.
   *
   * @param error The throwable to check
   * @return true if error is endpoint-related, false otherwise.
   */
  private boolean isEndpointError(Throwable error) {
    return error instanceof InvalidEndpointException ||
        (error.getMessage() != null && (error.getMessage().contains("UnknownHostException") ||
            error.getMessage().contains("Connection refused")));
  }

  /**
   * Closes the Token Manager, releasing resources and cancelling any ongoing refresh.
   * This ensures a clean shutdown scenario.
   */
  @Override
  public void close() {
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

  /**
   * Internal record for token info.
   *
   * This is used for RetryConfig type inference and can represent token data if needed.
   */
  private record TokenInfo(
      String tokenValue,
      OAuth2TokenType tokenType,
      Instant issuedAt,
      Instant expiresAt,
      Set<String> scopes) {

    TokenInfo {
      scopes = (scopes == null) ? Set.of() : Set.copyOf(scopes);
    }

    boolean isValid(Duration threshold) {
      return tokenValue != null && Instant.now().plus(threshold).isBefore(expiresAt);
    }
  }
}

