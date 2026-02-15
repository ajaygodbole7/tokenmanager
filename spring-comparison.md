# TokenManager vs Spring Security OAuth2 Client

Both can manage OAuth2 tokens for outbound API calls. They solve different problems. They're not mutually exclusive.

## TL;DR

- Spring Security OAuth2 Client = full OAuth2 implementation, framework-integrated
- TokenManager = focused resilience layer for service-to-service token management
- Spring has broader protocol coverage
- TokenManager has better behavior under failure

## When to use TokenManager

- Outbound calls to OAuth2-protected APIs using client credentials or service-account tokens
- You need circuit breaking, retry with jitter, graceful degradation — without wiring Resilience4j yourself
- You want single-flight refresh (one in-flight token request, everyone else waits)
- You don't want `spring-security-oauth2-client` and its transitive tree for a single token endpoint
- You're not in Spring at all — plain Java, Micronaut, Quarkus, CLI tools

## When to use Spring Security OAuth2 Client

- You're already using Spring Security for inbound auth
- You need the full login flow — authorization code with redirects, session management, CSRF
- You need token persistence (JDBC-backed `OAuth2AuthorizedClientRepository`)
- You need reactive/WebFlux support natively
- You want transparent token injection via `WebClient` or `RestClient` + `OAuth2AuthorizedClientManager`
- You need `private_key_jwt` client authentication
- You need automatic refresh token rotation
- Spring's `ClientCredentialsOAuth2AuthorizedClientProvider` handles client_credentials out of the box — if you don't need circuit breaking or graceful degradation, it may be enough

## When to use both

Different concerns:

- **Spring Security** → inbound authentication (users hitting your service)
- **TokenManager** → outbound tokens (your service calling downstream APIs)

A Spring Boot app can use Spring Security for user auth and TokenManager for machine-to-machine calls. This is the common pattern.

```java
@Configuration
public class AppConfig {

    // Inbound: Spring Security handles user authentication
    // (SecurityFilterChain config not shown)

    // Outbound: one TokenManager per downstream API
    @Bean(destroyMethod = "close")
    public OAuth2TokenManager paymentTokenManager(
            @Value("${payment.oauth.client-id}") String clientId,
            @Value("${payment.oauth.client-secret}") String clientSecret,
            @Value("${payment.oauth.token-endpoint}") String tokenEndpoint) {

        TokenConfig config = TokenConfig.builder()
            .clientId(clientId)
            .clientSecret(clientSecret)
            .tokenEndpoint(tokenEndpoint)
            .scope(Set.of("payments:write"))
            .build();

        return new OAuth2TokenManager(config);
    }

    @Bean(destroyMethod = "close")
    public OAuth2TokenManager analyticsTokenManager(
            @Value("${analytics.oauth.client-id}") String clientId,
            @Value("${analytics.oauth.client-secret}") String clientSecret,
            @Value("${analytics.oauth.token-endpoint}") String tokenEndpoint) {

        TokenConfig config = TokenConfig.builder()
            .clientId(clientId)
            .clientSecret(clientSecret)
            .tokenEndpoint(tokenEndpoint)
            .scope(Set.of("analytics:read"))
            .build();

        return new OAuth2TokenManager(config);
    }
}
```

## Feature comparison

| Feature | Spring Security OAuth2 Client | TokenManager |
|---|---|---|
| **Resilience** | | |
| Refresh coalescing | No — parallel requests possible without explicit coordination | Yes — single-flight, all callers share one future |
| Circuit breaker | No (DIY with Resilience4j) | Built-in, configurable |
| Retry with jitter | No (DIY with Resilience4j) | Built-in, exponential backoff ±50% jitter |
| Graceful degradation | No | Yes — transient failure + valid cache = cached token returned |
| **Protocol** | | |
| Client auth methods | `client_secret_post`, `client_secret_basic`, `client_secret_jwt`, `private_key_jwt` | `client_secret_post`, `client_secret_basic` only |
| Refresh token rotation | Automatic | Not handled |
| OAuth2 login flow | Yes — redirects, session, CSRF | No |
| OpenID Connect | Yes | No |
| DPoP | No | No |
| **Error handling** | | |
| Error classification | Standard Spring exception types | Sealed hierarchy — 5 subclasses, exhaustive `switch` |
| HTTPS enforcement | No constructor-time check | Yes — rejects non-HTTPS at construction |
| Secret redaction | Varies | `toString()` and exceptions tested for leakage |
| **Operational** | | |
| Reactive | Yes — `ReactiveOAuth2AuthorizedClientManager` | No — blocking. Wrap in `Mono.fromCallable()` if needed |
| Token persistence | Yes — JDBC | No — in-memory only |
| Metrics | Yes — Actuator / Micrometer | No — SLF4J logs only |
| **Integration** | | |
| Framework dependency | Spring Boot + Spring Security | None |
| HTTP client | `WebClient`, `RestClient` — token injection transparent | Returns `String` — use with anything |
| Non-Spring environments | Not practical | Micronaut, Quarkus, Dropwizard, CLI, plain Java |
| **Maturity** | | |
| Release status | Stable, LTS cadence, thousands of contributors | 0.1.0-SNAPSHOT, no compat guarantees |

## What Spring does better

- **Protocol breadth.** `private_key_jwt`, refresh token rotation, JDBC persistence, full OIDC, native reactive.
- **Transparent token injection.** `OAuth2AuthorizedClientManager` + `WebClient` means you never manually call `getToken()`. Tokens just show up on requests.
- **Battle-tested.** Years of production use across thousands of deployments. Active security advisory process.
- **Client credentials out of the box.** `ClientCredentialsOAuth2AuthorizedClientProvider` is Spring's direct competitor to TokenManager's core use case. It works. It just doesn't have resilience built in.
- **Ecosystem.** Actuator metrics, Spring Cloud integration, auto-configuration for common IdPs.

## What TokenManager does better

- **Single-flight refresh.** Hundreds of threads hit an expired token → one HTTP call, everyone else waits. Spring can trigger parallel requests without explicit coordination.
- **Circuit breaking.** Auth server down → circuit opens → stops hammering it → returns cached token if valid. Spring doesn't do this without custom Resilience4j wiring.
- **Graceful degradation.** Transient failure + valid cached token = silent success instead of a 500 to your caller.
- **Fail-fast on permanent errors.** Bad credentials throw immediately. Never hidden behind a stale cache. Spring doesn't distinguish permanent vs. transient at the type level.
- **Typed error handling.** Sealed hierarchy with 5 subclasses. Exhaustive `switch`. Each failure type maps to a distinct operational response. Spring gives you broader exception types that need message inspection.
- **No framework needed.** Works in plain Java, Micronaut, Quarkus, CLI tools. Spring's OAuth2 client is Spring-only.

## Further reading

- [README.md](README.md) — full docs, config reference, comparison with Nimbus/ScribeJava/Google Auth
- [README.md — comparison table](README.md#how-it-compares) — feature matrix across 5 libraries
