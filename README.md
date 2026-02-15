# OAuth2 Token Manager

Handles OAuth2 token caching, refresh, retries, and concurrent access. One method call returns a valid OAuth2 token or throws a classified `TokenException`.

Uses OkHttp internally for token endpoint requests and Resilience4j for circuit breaking and retry.

## Installation

```xml
<dependency>
    <groupId>org.tokenmanager</groupId>
    <artifactId>tokenmanager</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```groovy
implementation 'org.tokenmanager:tokenmanager:0.1.0-SNAPSHOT'
```

## Usage

### Plain Java

```java
TokenConfig config = TokenConfig.builder()
    .clientId("your-client-id")
    .clientSecret("your-client-secret")
    .tokenEndpoint("https://auth.example.com/token")
    .build();

OAuth2TokenManager tokenManager = new OAuth2TokenManager(config);

// use where needed
String token = tokenManager.getToken();
```

```java
// java.net.http
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/resource"))
    .header("Authorization", "Bearer " + tokenManager.getToken())
    .build();

// OkHttp
Request request = new Request.Builder()
    .url("https://api.example.com/resource")
    .addHeader("Authorization", "Bearer " + tokenManager.getToken())
    .build();
```

### Spring Boot

Already using Spring Security? See [how TokenManager complements Spring Security OAuth2 Client](spring-comparison.md).

Register the token manager as a bean. Spring handles shutdown via `destroyMethod`.

```java
@Configuration
public class TokenManagerConfig {

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
}
```

Inject and use:

```java
@Service
public class PaymentService {
    private final OAuth2TokenManager tokenManager;

    public PaymentService(OAuth2TokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    public Receipt charge(Order order) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://payments.example.com/charge"))
            .header("Authorization", "Bearer " + tokenManager.getToken())
            .POST(BodyPublishers.ofString(toJson(order)))
            .build();

        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        return parseReceipt(response.body());
    }
}
```

## Configuration

### Defaults

- Grant type: `CLIENT_CREDENTIALS`
  - HTTP timeout: 10s
  - Refresh threshold: 30s before expiry
  - Scope: none (set via `.scope(Set.of("read", "write"))`)
  - Client authentication: form POST (`client_secret_post`). Set `.clientAuthMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)` for providers that require HTTP Basic
  - HTTPS required (non-HTTPS endpoints rejected at construction time)

Override any default via `TokenConfig.builder()` unless noted.

### Supported grant types

| Grant type | Enum | Required fields |
|---|---|---|
| Client Credentials | `CLIENT_CREDENTIALS` | (default — clientId + clientSecret) |
| Password (Resource Owner Password Credentials) | `PASSWORD` | `username`, `password` |
| Authorization Code | `AUTHORIZATION_CODE` | `authorizationCode`, `redirectUri`; optional: `codeVerifier` (PKCE) |
| Refresh Token | `REFRESH_TOKEN` | `refreshToken` |
| JWT Bearer | `JWT_BEARER` | `assertion` |

Implicit grant is not supported (OAuth2 spec discourages it for server-side flows).

Password grant - Resource Owner Password Credentials (ROPC) is supported for legacy and migration scenarios where the client is fully trusted. OAuth 2.1 omits this grant type and modern security guidance discourages its use. This library does not restrict your choice of grant type; you own the risk decision.

TokenManager handles token endpoint exchanges only. Authorization redirects, PKCE flows, and token storage are your responsibility.

```java
// Password
TokenConfig.builder()
    .clientId("id").clientSecret("secret")
    .tokenEndpoint("https://auth.example.com/token")
    .grantType(OAuth2GrantType.PASSWORD)
    .username("user@example.com")
    .password("password")
    .build();

// Authorization Code with PKCE
TokenConfig.builder()
    .clientId("id").clientSecret("secret")
    .tokenEndpoint("https://auth.example.com/token")
    .grantType(OAuth2GrantType.AUTHORIZATION_CODE)
    .authorizationCode("SplxlOBeZQQYbYS6WxSbIA")
    .redirectUri("https://app.example.com/callback")
    .codeVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
    .build();

// Refresh Token
TokenConfig.builder()
    .clientId("id").clientSecret("secret")
    .tokenEndpoint("https://auth.example.com/token")
    .grantType(OAuth2GrantType.REFRESH_TOKEN)
    .refreshToken("tGzv3JOkF0XG5Qx2TlKWIA")
    .build();

// JWT Bearer
TokenConfig.builder()
    .clientId("id").clientSecret("secret")
    .tokenEndpoint("https://auth.example.com/token")
    .grantType(OAuth2GrantType.JWT_BEARER)
    .assertion("eyJhbGciOi...")
    .build();
```

### Client authentication method

The default is `client_secret_post` (credentials in form body). For providers that require HTTP Basic authentication (Azure AD, some Keycloak configurations):

```java
TokenConfig config = TokenConfig.builder()
    .clientId("id").clientSecret("secret")
    .tokenEndpoint("https://auth.example.com/token")
    .clientAuthMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
    .build();
```

### Custom HTTP client

The library creates its own OkHttp client by default. To supply a custom HTTP client (e.g., for custom TLS or interceptors):

```java
TokenConfig config = TokenConfig.builder()
    .clientId("id").clientSecret("secret")
    .tokenEndpoint("https://auth.example.com/token")
    .httpClient(yourOkHttpClient)
    .build();
```

> **Note:** If you pass your own client, the TokenManager uses it but does not close it.

## Error handling

`getToken()` throws `TokenException`. Five sealed subclasses cover every failure mode:

| Exception | Meaning | Trigger |
|---|---|---|
| `InvalidCredentialsException` | Bad credentials | 401, 403, `invalid_client`, `invalid_grant` |
| `InvalidConfigurationException` | Bad config | `invalid_request`, `invalid_scope`, `unsupported_grant_type` |
| `InvalidEndpointException` | Unreachable endpoint | DNS failure, non-auth 4xx |
| `RateLimitedException` | Rate limited | 429 |
| `ServiceUnavailableException` | Transient failure | 5xx, timeout, circuit breaker open |

```java
try {
    String token = tokenManager.getToken();
} catch (TokenException e) {
    // Exhaustive pattern matching (Java 21+)
    switch (e) {
        case InvalidCredentialsException ex -> alertOps(ex);
        case InvalidConfigurationException ex -> failFast(ex);
        case InvalidEndpointException ex -> checkDns(ex);
        case RateLimitedException ex -> backOff(ex);
        case ServiceUnavailableException ex -> retryLater(ex);
    }
}
```

## Concurrency and resilience

- `getToken()` is thread-safe and may be called concurrently from any number of threads
  - `getToken()` is a blocking call. Tokens are cached in-memory per `OAuth2TokenManager` instance (not shared across JVMs)
  - At most one in-flight refresh per manager instance at a time; concurrent callers share it. No thread storms, no duplicate fetches
  - If a transient refresh failure occurs (5xx, timeout, 429, circuit breaker open) and the cached token is still valid, `getToken()` returns the cached token. Permanent failures (invalid credentials, bad configuration, unreachable endpoint) throw immediately — they are never masked by a cached token
  - Circuit breaker opens after consecutive transient failures (5xx, network errors, timeouts), 60s cooldown. Credential, configuration, and endpoint errors do not trip it
  - Retry with exponential backoff and ±50% jitter, 3 attempts. Retries cover network-level failures only (connection resets, socket timeouts). Server errors (5xx), auth errors, and rate limits are not retried — the circuit breaker and graceful degradation handle those
  - Rate-limited (429) responses do not trip the circuit breaker and are not retried. If the cached token is still valid, `getToken()` returns it

## Security

Tokens are stored in-memory only and never persisted to disk.

## Resource management

- `OAuth2TokenManager` implements `AutoCloseable`
  - `close()` is idempotent
  - `getToken()` after `close()` throws `IllegalStateException`

## Requirements

- Java 21+ (sealed classes, pattern matching, records, virtual threads)
  - Maven

### Tested with (pre-release)

| Dependency | Version |
|---|---|
| JDK | 21+ |
| OkHttp | 5.3.2 |
| Resilience4j | 2.3.0 |
| Jackson | 2.18.2 |

No compatibility guarantees until 1.0.0.
