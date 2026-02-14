# TokenManager vs Spring Security OAuth2 Client

## When to use TokenManager

- Your service calls OAuth2-protected APIs using client credentials or service-account tokens
- You need resilience (circuit breaker, retry with jitter, graceful degradation) without wiring Resilience4j yourself
- You want refresh coalescing (single-flight refresh). In many Spring setups, concurrent outbound calls can still trigger parallel token requests unless you add explicit coordination or caching
- You don't want to pull in `spring-security-oauth2-client` and its transitive dependencies for a single token endpoint call. TokenManager's dependency surface is OkHttp + Jackson + Resilience4j (no Spring)
- You're not in Spring at all (plain Java, Micronaut, Quarkus, CLI tools)

TokenManager is blocking. For WebFlux or reactive stacks, prefer Spring Security's OAuth2 client.

## When to use Spring Security OAuth2 Client

- You're already using Spring Security for authentication/authorization in your app
- You need the full OAuth2 login flow (authorization code with redirects, session management, CSRF)
- You need token persistence (JDBC-backed `OAuth2AuthorizedClientRepository`)
- You need reactive/WebFlux support
- You want integration with Spring's `WebClient` or `RestClient` via `OAuth2AuthorizedClientManager`

## When to use both

TokenManager manages tokens for outbound service-to-service calls. Spring Security manages inbound authentication. These are different concerns. A Spring Boot app can use Spring Security to authenticate users and TokenManager to call downstream APIs with client credentials.

```java
@Configuration
public class AppConfig {

    // Spring Security handles inbound user authentication
    // (configured via SecurityFilterChain, not shown)

    // TokenManager handles outbound service-to-service tokens
    @Bean(destroyMethod = "close")
    public OAuth2TokenManager paymentTokenManager(
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

## Feature comparison

| Feature | Spring Security OAuth2 Client | TokenManager |
|---|---|---|
| Refresh coalescing | Requires explicit coordination/caching to prevent parallel token requests under contention | Yes (single-flight refresh) |
| Circuit breaker | No (layer Resilience4j yourself) | Built-in |
| Retry with jitter | No (layer Resilience4j yourself) | Built-in |
| Graceful degradation | No built-in graceful degradation | Yes (returns cached token if still valid; otherwise fails fast with classified error) |
| HTTPS enforcement | No built-in constructor-time enforcement | Yes (rejects non-HTTPS at construction) |
| Error classification | Error codes + general exception types | Sealed hierarchy (4 subclasses, exhaustive switch) |
| Client auth methods | Multiple (e.g., Basic, Post; configurable) | `client_secret_post` only *(client_secret_basic planned)* |
| Framework dependency | Spring | None |
| Reactive support | Yes (WebFlux) | No (blocking) |
| Token persistence | Yes (JDBC) | No (in-memory only) |
| OAuth2 login flow | Yes | No (token endpoint exchange only) |
