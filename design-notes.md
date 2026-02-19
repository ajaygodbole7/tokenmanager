# Design Notes

Internal design rationale for `tokenmanager`. Read the [README](README.md) for usage.

## Request collapsing

When a token crosses the `refreshThreshold`, the first thread to enter `refreshToken()` creates a `CompletableFuture`. All subsequent threads return the same future. One HTTP call per refresh cycle, regardless of concurrency.

This prevents a burst of token requests during global restarts or traffic spikes from overwhelming the Identity Provider (IdP).

## Failure classification

Exceptions are classified as permanent or transient. The distinction drives every downstream decision: retry, circuit breaker recording, and graceful degradation.

| Category | Exception types | Retried | Trips CB | Graceful degradation |
|---|---|---|---|---|
| Permanent | `InvalidCredentialsException`, `InvalidConfigurationException`, `InvalidEndpointException` | No | No (counted as success) | No — throws immediately |
| Transient | `ServiceUnavailableException`, `UncheckedIOException` | `UncheckedIOException` only | Yes | Yes — returns cached token if unexpired |
| Rate limit | `RateLimitedException` | No | No (ignored) | Yes — returns cached token if unexpired |

Permanent errors throw immediately and never mask behind a stale cache. A rotated credential surfaces on the next `getToken()`, not whenever the old token happens to expire.

## Circuit breaker: why 100% failure threshold

The failure rate threshold is hardcoded to 100%. The circuit opens only when every call in the sliding window fails.

Rationale: in a Machine-to-Machine (M2M) context, a single successful refresh is a force multiplier — it buys the full `expires_in` window (typically 3600 seconds) of valid tokens for all threads. During an IdP brownout (90% failure rate), the retry mechanism (3 attempts with exponential backoff) gives each refresh cycle a ~27% chance of at least one success (1 - 0.9^3). Each success resets the token validity window.

If the threshold were 50%, the circuit would open during brownout. For 60 seconds: zero refresh attempts. Then one half-open probe with a 90% chance of failure, re-opening the circuit. The system gets stuck in an open/half-open cycle while the token cache drains.

The 100% threshold means: keep trying during brownout (retry + graceful degradation handle partial failures), stop only when the server is completely unresponsive.

## Graceful degradation

When a refresh fails with a transient error and the cached token has not reached its hard `expiresAt` timestamp, `getToken()` returns the cached token and logs a warning. Availability over freshness.

This does not apply to permanent errors. Bad credentials, bad configuration, and unreachable endpoints always throw — a stale token would just defer the failure to the downstream service.

## Virtual thread compatibility

The library uses `ReentrantLock` instead of `synchronized` for all critical sections. `ReentrantLock` does not pin virtual thread carriers, so the library works correctly on `Executors.newVirtualThreadPerTaskExecutor()` (which it uses internally).

Lock hold times are minimal: reading/writing `ongoingRefresh` and `currentToken`. No I/O occurs under lock.

## Response body size cap

Response bodies are read up to 64 KB (`MAX_RESPONSE_BODY_BYTES`) via okio `BufferedSource.request()`. This prevents out-of-memory conditions from malicious or misconfigured IdP responses. Truncated responses are logged as warnings.
