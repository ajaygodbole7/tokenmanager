# TokenManager — Future Enhancements

Deferred items from code reviews. None are blockers for 0.1.0. All are non-breaking additions that can be evaluated based on real-world adoption feedback.

---

## Behavioral

### 1. Configurable fallback on permanent refresh failures

Allow returning a cached unexpired token when refresh fails with a permanent error (e.g., `InvalidCredentialsException` during credential rotation).

**Why deferred:** Current fail-fast behavior is intentional and documented in `design-notes.md`. Surfacing credential errors within `refreshThreshold` seconds is operationally preferable to discovering them an hour later at hard expiry. Adding a behavioral flag (`fallbackOnPermanentErrors`) to a library with zero behavioral flags is a complexity cost.

**When to revisit:** If multiple adopters report that credential rotation brownouts cause unnecessary outages and they'd prefer run-until-expiry semantics.

**Shape:** `TokenConfig.builder().fallbackOnPermanentErrors(true)` (default `false`). Extend `tryGracefulDegradation` to include `InvalidCredentialsException` when enabled.

### 2. Configurable token expiry leeway

Subtract a safety buffer from `expires_in` to account for clock skew and network latency.

**Why deferred:** `refreshThreshold` (default 30s) already absorbs latency, skew, and propagation delay. A second overlapping time-based knob creates confusing interactions. Defaulting to `Duration.ZERO` means it does nothing unless configured — an API surface cost with no default benefit.

**When to revisit:** If adopters operate in high-latency environments (satellite links, cross-region IdPs) where 30s refresh threshold is insufficient and they can't increase it.

**Shape:** `TokenConfig.builder().tokenExpiryLeeway(Duration.ofSeconds(5))` (default `ZERO`). Applied in `createOAuth2TokenFromNode` before computing `expiresAt`.

---

## Extensibility

### 3. Executor injection

Allow callers to provide their own `ExecutorService` instead of the hardcoded `Executors.newVirtualThreadPerTaskExecutor()`.

**Why deferred:** Library requires Java 25. Single-flight design means at most one virtual thread does blocking I/O at any time. No real scenario where overriding the executor helps. Adds an ownership/lifecycle pattern (`ownsExecutor`) for zero practical benefit today.

**When to revisit:** If the library is backported to Java 17 (where virtual threads are preview), or if adopters demonstrate a concrete need for bounded thread pools in their environment.

**Shape:** `TokenConfig.builder().executor(myExecutor)`. Manager uses it if non-null, creates virtual thread executor otherwise. Manager does not close caller-provided executor (matches `httpClient` lifecycle pattern).

### 4. HTTP client abstraction (SPI)

Decouple from OkHttp via an interface so adopters on Apache HttpClient or Java's built-in `HttpClient` don't need OkHttp on the classpath.

**Why deferred:** OkHttp 5.x is stable, widely used, and the library's HTTP needs are minimal (one POST per refresh). An SPI adds interface design, adapter implementations, and testing surface for marginal adoption benefit.

**When to revisit:** If adopters report classpath conflicts with OkHttp or organizational mandates against it.

### 5. Metrics / observability hooks

Expose token refresh success/failure counts, latencies, circuit breaker state, and cache hit rates via a callback interface or Micrometer integration.

**Why deferred:** The library logs state transitions and failures at appropriate levels. Structured metrics are a significant API surface commitment (what to expose, naming conventions, Micrometer vs. callback vs. both). Previously proposed as `Consumer<TokenEvent>` during staff review and rejected as overcomplication; SLF4J is the monitoring hook.

**When to revisit:** When multiple adopters need dashboard integration and the logging output proves insufficient.

---

## Standards / Interoperability

### 6. RFC 6749 §2.3.1 URL-encoding for Basic auth

URL-encode `client_id` and `client_secret` before Base64 encoding in `CLIENT_SECRET_BASIC` mode, per strict RFC 6749 reading.

**Why deferred:** The proposed implementation double-encodes (URL-encode then Base64-encode). Most real-world IdPs (Okta, Auth0, Keycloak, Azure AD) do not URL-decode after Base64-decode. The current `Credentials.basic()` with UTF-8 works correctly against all tested IdPs. Implementing strict RFC encoding would break authentication against the majority of real servers.

**When to revisit:** If a specific IdP requires this encoding. Implementation should be opt-in (`ClientAuthMethod.CLIENT_SECRET_BASIC_RFC6749`) rather than changing default behavior.

### 7. Refresh token capture from token response

Capture `refresh_token` from the token response and use it for subsequent refreshes instead of replaying the original grant parameters.

**Why deferred:** The library's primary targets are `CLIENT_CREDENTIALS` and `JWT_BEARER`, which don't issue refresh tokens. Supporting refresh token rotation properly requires state management (storing the latest refresh token) and changes to the grant type handling that add complexity for a secondary use case.

**When to revisit:** If adopters need `AUTHORIZATION_CODE` + refresh token rotation as a first-class flow.

---

## Operational

### 8. Configurable `unsafeAllowHttp` for dev/test environments

Allow `http://` token endpoints behind an explicit opt-in flag.

**Why deferred:** Not planned. Creates a security footgun that someone will accidentally ship to production. Every serious IdP uses HTTPS. Tests already solve this with MockWebServer + TLS.

**When to revisit:** Only if a compelling use case appears where local TLS is genuinely impossible. Even then, environment-variable gating is safer than a config flag.

### 9. Refresh threshold vs. token lifetime clamping

Automatically reduce effective `refreshThreshold` if the IdP issues tokens with `expires_in` shorter than the threshold (e.g., 30s tokens with 30s threshold = refresh every call).

**Why deferred:** Silent behavior change violates principle of least surprise. The library already logs a one-time warning when this condition is detected (`shortLivedTokenWarningLogged`). Documentation in `TokenConfig` Javadoc explains the interaction.

**When to revisit:** If the warning isn't sufficient and adopters frequently misconfigure this. A log-then-clamp approach (warn + reduce to 50% of token lifetime) could work as opt-in.

---

## Roadmap

Release decisions are driven by adoption feedback, not a predetermined feature list. Ship 1.0 when the API is stable, not when a checklist is complete.

### 0.1.0 — Initial release

Current codebase. Remaining work: GitHub Actions CI workflow (`mvn verify` + dependency scanning on PRs, GPG-signed release process).

**Gate:** All unit tests pass, CI green, non-SNAPSHOT version published to Maven Central.

### 0.2.0 — Adoption-driven hardening

Scope determined by early adopter feedback. Candidates in likely priority order:

- **Fallback on permanent refresh failures** (#1) — if credential rotation pain is confirmed
- **Observability hooks** (#5) — if logging output proves insufficient for production dashboards
- **Refresh token capture** (#7) — if authorization_code flow demand materializes

Only items with concrete adoption evidence get included.

### 1.0.0 — API stability commitment

Ship when the public API (`TokenProvider`, `TokenConfig`, `TokenException` hierarchy) has been stable across 0.x releases and no breaking changes are anticipated. Could be 0.2.0 promoted directly if the API doesn't change.

**Gate:** No public API changes for at least one minor release. README, Javadoc, and design-notes consistent with shipped behavior.

### Timeline

| Milestone | Target | Dependency |
|-----------|--------|------------|
| 0.1.0 | When CI is green | GitHub Actions workflow |
| 0.2.0 | 4–8 weeks after 0.1.0 | At least one adopter's production feedback |
| 1.0.0 | When API is stable across 0.x | No breaking changes pending |

The timeline is intentionally loose. Shipping 1.0.0 two weeks after 0.1.0 is fine if nothing changes. Waiting six months is also fine if the API needs iteration. Version numbers communicate stability, not effort.
