
# OAuth2 Token Manager

Handles OAuth2 token caching, refresh, retries, and concurrent access for service-to-service calls.

- One method: `getToken()` returns a valid token or throws a classified `TokenException`
- Uses OkHttp for token endpoint requests
- Uses Resilience4j for circuit breaking and retry

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

try (OAuth2TokenManager tokenManager = new OAuth2TokenManager(config)) {
    String token = tokenManager.getToken();
}
```

Works with any HTTP client:

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

Register as a bean. Spring calls `close()` on shutdown.

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

| Setting | Default | Override |
|---|---|---|
| Grant type | `CLIENT_CREDENTIALS` | `.grantType(OAuth2GrantType.PASSWORD)` |
| HTTP timeout | 10s | `.httpTimeout(Duration.ofSeconds(15))` |
| Refresh threshold | 30s before expiry | `.refreshThreshold(Duration.ofSeconds(60))` |
| Scope | none | `.scope(Set.of("read", "write"))` |
| Client auth | `client_secret_post` (form body) | `.clientAuthMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)` |
| HTTPS | required | non-HTTPS rejected at construction |
| Max retries | 3 | `.maxRetryAttempts(2)` |
| Initial retry delay | 1s | `.initialRetryDelay(Duration.ofMillis(500))` |
| CB minimum calls | 3 | `.circuitBreakerMinimumCalls(5)` |
| CB wait duration | 60s | `.circuitBreakerWaitDuration(Duration.ofSeconds(30))` |

**Heads up:** `TokenConfig.validate()` runs in the `OAuth2TokenManager` constructor, not at `.build()`. You can create an invalid config and not find out until you instantiate the manager.

### Supported grant types

| Grant type | Enum | Required fields |
|---|---|---|
| Client Credentials | `CLIENT_CREDENTIALS` | (default — clientId + clientSecret) |
| Password (Resource Owner Password Credentials) | `PASSWORD` | `username`, `password` |
| Authorization Code | `AUTHORIZATION_CODE` | `authorizationCode`, `redirectUri`; optional: `codeVerifier` (PKCE) |
| Refresh Token | `REFRESH_TOKEN` | `refreshToken` |
| JWT Bearer | `JWT_BEARER` | `assertion` |

- Implicit grant is not supported — the spec discourages it for server-side flows
- Password grant (Resource Owner Password Credentials) is here for legacy/migration — OAuth 2.1 drops it entirely

> **Read this before using non-client-credentials grants:**
>
> - **`CLIENT_CREDENTIALS` is the primary use case.** Only grant type that works reliably across multiple refresh cycles without external coordination.
> - **Authorization codes are single-use.** TokenManager replays the configured code on every refresh. After the first exchange, subsequent refreshes fail with `invalid_grant`. If the response includes a `refresh_token`, create a new manager with `REFRESH_TOKEN` grant.
> - **Refresh token rotation is not handled.** Some servers issue a new `refresh_token` with each response (RFC 6749 §6). TokenManager doesn't capture it. If your provider rotates, the old token gets invalidated.
> - **Token endpoint exchanges only.** No authorization redirects. No PKCE code generation. No session management. Use Spring Security or a full OAuth2 client for those.

```java
// Client Credentials (recommended for service-to-service)
TokenConfig.builder()
    .clientId("id").clientSecret("secret")
    .tokenEndpoint("https://auth.example.com/token")
    .build();

// Password (legacy — see caveats above)
TokenConfig.builder()
    // ... base config ...
    .grantType(OAuth2GrantType.PASSWORD)
    .username("user@example.com")
    .password("password")
    .build();

// Refresh Token (rotation caveat above)
TokenConfig.builder()
    // ... base config ...
    .grantType(OAuth2GrantType.REFRESH_TOKEN)
    .refreshToken("tGzv3JOkF0XG5Qx2TlKWIA")
    .build();

// JWT Bearer
TokenConfig.builder()
    // ... base config ...
    .grantType(OAuth2GrantType.JWT_BEARER)
    .assertion("eyJhbGciOi...")
    .build();

// Authorization Code with PKCE (single-use — see caveats above)
TokenConfig.builder()
    // ... base config ...
    .grantType(OAuth2GrantType.AUTHORIZATION_CODE)
    .authorizationCode("SplxlOBeZQQYbYS6WxSbIA")
    .redirectUri("https://app.example.com/callback")
    .codeVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
    .build();
```

### Client authentication

Default is `client_secret_post` (credentials in form body).

For providers that want HTTP Basic (Azure AD, some Keycloak setups):

```java
TokenConfig config = TokenConfig.builder()
    .clientId("id").clientSecret("secret")
    .tokenEndpoint("https://auth.example.com/token")
    .clientAuthMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
    .build();
```

### Custom HTTP client

TokenManager creates its own OkHttp client. Pass your own for custom TLS, mTLS, proxy, or interceptors:

```java
TokenConfig config = TokenConfig.builder()
    .clientId("id").clientSecret("secret")
    .tokenEndpoint("https://auth.example.com/token")
    .httpClient(yourOkHttpClient)
    .build();
```

- If you pass your own client, TokenManager uses it but doesn't close it
- You own the lifecycle

## Error handling

`getToken()` throws `TokenException`. Five sealed subclasses:

| Exception | Meaning | Trigger |
|---|---|---|
| `InvalidCredentialsException` | Bad credentials | 401, 403, `invalid_client`, `invalid_grant` |
| `InvalidConfigurationException` | Bad config | `invalid_request`, `invalid_scope`, `unsupported_grant_type` |
| `InvalidEndpointException` | Unreachable endpoint | DNS failure, non-auth 4xx |
| `RateLimitedException` | Rate limited | 429 (parses `Retry-After`) |
| `ServiceUnavailableException` | Transient failure | 5xx, timeout, circuit breaker open |

Sealed hierarchy = exhaustive `switch` in Java 21+:

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

## Concurrency and resilience

Call `getToken()` from any thread.

- **Single-flight refresh.** One HTTP refresh in flight at a time. Everyone else waits on the same `CompletableFuture`. No thread storms. No duplicate fetches.
- **Graceful degradation.** Transient failure + cached token still valid = you get the cached token.
- **Fail-fast on permanent errors.** Credential, configuration, and endpoint errors throw immediately. Never masked by a stale cache.
- **Credential rotation surfaces fast.** A rotated credential fails on the next `getToken()`, not whenever the old token happens to expire.
- **Circuit breaker.** Opens after consecutive transient failures, 60s cooldown. Credential/config/endpoint errors don't trip it. 429s don't trip it.
- **Retry.** Exponential backoff + ±50% jitter, 3 attempts. Network-level only — connection resets, socket timeouts.
- **Not retried:** server errors (5xx), auth errors, rate limits. Circuit breaker and graceful degradation handle those.
- **429 handling.** Classified by status code, body ignored. `RateLimitedException` includes parsed `Retry-After`.
- **Cache scope.** In-memory, per instance. Not shared across JVMs.

## Security

- **HTTPS enforced.** Non-HTTPS endpoints rejected at construction time.
- **Secrets redacted.** `toString()` never includes client secrets, passwords, or token values.
- **Exceptions clean.** Messages and cause chains tested against credential leakage.
- **In-memory only.** Tokens never hit disk.
- **Tokens never logged.** Only masked prefixes in debug output.

## Resource management

`OAuth2TokenManager` implements `AutoCloseable`.

- `close()` is idempotent
- `getToken()` after `close()` throws `IllegalStateException`
- `close()` cancels in-flight refreshes, shuts down the executor, cleans up OkHttp (unless you BYO'd the client)

## What this does not do

One job: manage a single OAuth2 token with resilience.

- **No login flows.** No redirects, no sessions, no CSRF.
- **No token introspection.** Trusts `expires_in`. No RFC 7662. No JWT verification.
- **No token revocation.** No RFC 7009.
- **No OpenID Connect.** No `id_token`, no UserInfo, no discovery.
- **No `private_key_jwt`.** No `tls_client_auth`. Only `client_secret_post` and `client_secret_basic`.
- **No DPoP.** Required by some financial-grade APIs. Not yet.
- **No reactive API.** Blocking only. Wrap in `Mono.fromCallable()` if needed.
- **No persistent cache.** In-memory. Cold starts re-fetch.
- **No multi-tenant.** One manager = one client = one endpoint.
- **No metrics.** No Micrometer. Logs only.

## How it compares

See [spring-comparison.md](spring-comparison.md) for Spring-specific comparison with code.

| Capability | TokenManager | Nimbus SDK | Spring OAuth2 | ScribeJava | Google Auth |
|---|---|---|---|---|---|
| **Focus** | Token cache + resilience | Full OAuth2/OIDC protocol | Spring-integrated OAuth2 | OAuth flow execution | Google API auth |
| Token caching | Yes | No | Yes | No | Yes |
| Single-flight refresh | Yes | N/A | No | No | No |
| Circuit breaker | Built-in | No | No | No | No |
| Retry with jitter | Built-in | No | No | No | Google only |
| Graceful degradation | Yes | N/A | No | No | No |
| Typed errors | Sealed, 5 subclasses | No | No | No | No |
| DPoP | No | Yes | No | No | No |
| `private_key_jwt` | No | Yes | Yes | No | Yes |
| Token introspection | No | Yes | No | No | No |
| Token revocation | No | Yes | No | Yes | Yes |
| Refresh token rotation | No | N/A | Yes | Manual | Yes |
| OpenID Connect | No | Full | Full | Partial | Google-specific |
| Reactive | No | N/A | Yes | Yes | Yes |
| Token persistence | No | N/A | Yes (JDBC) | No | Yes (file) |
| HTTP client | OkHttp (BYO instance) | Pluggable | WebClient / RestClient | JDK, OkHttp, Ning, Apache | Google HTTP |
| Framework needed | None | None | Spring | None | Google HTTP |
| Min Java | 21 | 8 | 17 | 7 | 8 |
| Maturity | 0.1.0 pre-release | 11.x, 10+ years | Spring LTS | 8.x stable | Google-maintained |

### When to use TokenManager

- Outbound service-to-service calls with client credentials
- You want caching + retry + circuit breaking without wiring it yourself
- You may or may not be using Spring
- You want typed errors you can `switch` on

### When to use something else

- **Need full OAuth2 protocol?** Login flows, DPoP, JWT client auth, introspection → Nimbus SDK
- **All-in on Spring?** Framework integration, reactive, token persistence → Spring OAuth2 Client
- **Talking to Google?** → Google Auth Library
- **Need pluggable HTTP clients?** → ScribeJava

TokenManager handles the resilience layer these don't. You can use them together.

## Dependencies

| Dependency | Why | Note |
|---|---|---|
| OkHttp 5.x | HTTP client | Brings `kotlin-stdlib` (~1.7 MB) |
| Jackson Databind | JSON parsing | |
| Resilience4j | Circuit breaker + retry | Uses `resilience4j-all`; only CB and retry are used |
| SLF4J API | Logging facade | |
| Lombok | Code generation | `provided` scope, not runtime |

## Requirements

- Java 21+
- Maven

### Tested with

| Dependency | Version |
|---|---|
| JDK | 21+ |
| OkHttp | 5.3.2 |
| Resilience4j | 2.3.0 |
| Jackson | 2.18.2 |

Pre-release. No compatibility guarantees until 1.0.
