# OAuth2 Token Manager

Caches an OAuth2 access token in memory, refreshes it before expiry, and retries on transient failures.

`getToken()` returns a valid token or throws a classified exception. `getTokenAsync()` returns a `CompletableFuture<String>` for non-blocking access. Call either from any thread.

```java
TokenConfig config = TokenConfig.builder()
    .clientId("your-client-id")
    .clientSecret("your-client-secret")
    .tokenEndpoint("https://auth.example.com/token")
    .build();

try (OAuth2TokenManager tokenManager = new OAuth2TokenManager(config)) {
    String token = tokenManager.getToken();
}
```

Requires Java 25+. No framework dependency. `getToken()` returns a `String` — use it with any HTTP client.

## Installation

```xml
<dependency>
    <groupId>io.github.ajaygodbole7</groupId>
    <artifactId>tokenmanager</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## How it works

Multiple threads calling `getToken()` at the same time trigger a single HTTP request. Everyone else blocks on the same `CompletableFuture`.

If a refresh fails with a transient error and the cached token is still valid, `getToken()` returns the cached token.

If a refresh fails with a permanent error (bad credentials, bad config, unreachable endpoint), the exception propagates immediately. Permanent errors are never masked by a stale cache.

Call `invalidate()` if a downstream service rejects the token (server-side revocation). The next `getToken()` fetches fresh.

```java
tokenManager.invalidate();
String freshToken = tokenManager.getToken();
```

A circuit breaker opens after consecutive transient failures. Default cooldown is 60 seconds. Credential and configuration errors do not trip the circuit breaker.

Retries use exponential backoff with ±50% jitter, up to 3 attempts. Only network-level failures are retried — connection resets and socket timeouts. Server errors (5xx), auth errors, and rate limits are not retried.

If the server returns 429 with a `Retry-After` header, the manager pauses refresh attempts for the specified duration. During the cooldown, `getToken()` returns the cached token if it hasn't expired, or throws `RateLimitedException` if it has.

## Error handling

`getToken()` throws `TokenException`. There are five sealed subclasses, so the `switch` is exhaustive:

```java
try {
    String token = tokenManager.getToken();
} catch (TokenException e) {
    switch (e) {
        case InvalidCredentialsException ex -> alertOps(ex);
        case InvalidConfigurationException ex -> failFast(ex);
        case InvalidEndpointException ex -> checkDns(ex);
        case RateLimitedException ex -> backOff(ex.getRetryAfter());
        case ServiceUnavailableException ex -> retryLater(ex);
    }
}
```

| Exception | Trigger |
|---|---|
| `InvalidCredentialsException` | 401/403, `invalid_client`, `invalid_grant` |
| `InvalidConfigurationException` | `invalid_request`, `invalid_scope`, `unsupported_grant_type` |
| `InvalidEndpointException` | DNS failure, non-auth 4xx |
| `RateLimitedException` | 429 (parses `Retry-After`) |
| `ServiceUnavailableException` | 5xx, timeout, circuit breaker open |

## Configuration

### Defaults

| Setting | Default | Override |
|---|---|---|
| Grant type | `CLIENT_CREDENTIALS` | `.grantType(OAuth2GrantType.JWT_BEARER)` |
| HTTP timeout | 10s | `.httpTimeout(Duration.ofSeconds(15))` |
| Refresh threshold | 30s before expiry | `.refreshThreshold(Duration.ofSeconds(60))` |
| Scope | none | `.scope(Set.of("read", "write"))` |
| Client auth | `client_secret_post` | `.clientAuthMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)` |
| Max retries | 3 | `.maxRetryAttempts(2)` |
| Initial retry delay | 1s | `.initialRetryDelay(Duration.ofMillis(500))` |
| CB minimum calls | 3 | `.circuitBreakerMinimumCalls(5)` |
| CB wait duration | 60s | `.circuitBreakerWaitDuration(Duration.ofSeconds(30))` |

Non-HTTPS endpoints are rejected at construction time.

`TokenConfig.validate()` runs in the `OAuth2TokenManager` constructor, not at `.build()`.

### Grant types

Two grant types work across refresh cycles:

| Grant type | Enum | Required fields |
|---|---|---|
| Client Credentials | `CLIENT_CREDENTIALS` | clientId + clientSecret (default) |
| JWT Bearer | `JWT_BEARER` | `assertion` (backed by a `Supplier<String>`, called on every refresh) |

```java
// JWT Bearer with assertion regenerated on each refresh
TokenConfig.builder()
    .clientId("service-account")
    .clientSecret("secret")
    .grantType(OAuth2GrantType.JWT_BEARER)
    .assertionSupplier(() -> signJwt(privateKey))
    .tokenEndpoint("https://auth.example.com/token")
    .build();
```

The enum also includes `PASSWORD`, `AUTHORIZATION_CODE`, and `REFRESH_TOKEN`. These perform the first token exchange but break on subsequent refreshes because the library replays the original grant parameters.

Authorization codes are single-use. The second refresh fails with `invalid_grant`.

Refresh tokens may be rotated server-side. The library does not capture the new token from the response.

Password (ROPC) replays the credentials, but OAuth 2.1 drops this grant entirely.

`IMPLICIT` is rejected at validation time.

### Client authentication

Default is `client_secret_post` (credentials in form body).

For providers that require HTTP Basic:

```java
TokenConfig.builder()
    // ...
    .clientAuthMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
    .build();
```

### Custom HTTP client

Pass an OkHttp client for custom TLS, mTLS, proxy, or interceptors:

```java
TokenConfig.builder()
    // ...
    .httpClient(yourOkHttpClient)
    .build();
```

If you provide your own client, you own its lifecycle. TokenManager will not close it.

A custom client must be built with `followRedirects(false)` and `followSslRedirects(false)`;
configuration validation rejects it otherwise. The token request carries the client secret
(or assertion) in its form body, which OkHttp would replay to another host on a 307/308
redirect.

## Spring Boot

Register as a bean. Spring calls `close()` on shutdown.

```java
@Bean(destroyMethod = "close")
public OAuth2TokenManager paymentAuthManager(
        @Value("${payment.oauth.client-id}") String clientId,
        @Value("${payment.oauth.client-secret}") String clientSecret,
        @Value("${payment.oauth.token-endpoint}") String tokenEndpoint) {

    TokenConfig config = TokenConfig.builder()
        .clientId(clientId)
        .clientSecret(clientSecret)
        .tokenEndpoint(tokenEndpoint)
        .build();

    return new OAuth2TokenManager(config);
}
```

Use Spring Security for inbound user auth. Use TokenManager for outbound machine-to-machine calls. See [spring-comparison.md](spring-comparison.md) for a detailed comparison.

## Security

`toString()` and exception messages do not include secrets or token values.

Tokens are held in memory only and are not written to logs or disk.

## Resource management

`OAuth2TokenManager` implements `AutoCloseable`.

`close()` is idempotent. It cancels in-flight refreshes and shuts down the internal executor.

`getToken()` after `close()` throws `IllegalStateException`.

## Scope

This library manages a single OAuth2 token per instance. One manager, one client, one endpoint.

It does not handle login flows, token introspection, token revocation, OpenID Connect, DPoP, `private_key_jwt`, reactive streams, persistent caching, or metrics export.

For protocol coverage beyond token endpoint exchanges, see [Nimbus OAuth2 SDK](https://connect2id.com/products/nimbus-oauth-openid-connect-sdk) or [Spring Security OAuth2 Client](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/index.html). Either can be used alongside TokenManager.

## Dependencies

OkHttp 5.x, Jackson Databind, Resilience4j, SLF4J. See `pom.xml` for versions.

## Status

0.1.0-SNAPSHOT. Pre-release. API may change before 1.0.

## License

[Apache License 2.0](LICENSE)
