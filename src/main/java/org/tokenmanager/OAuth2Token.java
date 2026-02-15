package org.tokenmanager;

import lombok.NonNull;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Represents an OAuth2 token with its metadata.
 * Immutable value class implemented as a record.
 */
public record OAuth2Token(
    String tokenValue,
    OAuth2TokenType tokenType,
    @NonNull Instant issuedAt,
    @NonNull Instant expiresAt,
    Set<String> scopes
) {
  /**
   * Creates a new OAuth2Token with validation
   */
  public OAuth2Token {
    // Validate required fields
    if (tokenValue.isBlank()) {
      throw new IllegalArgumentException("tokenValue cannot be blank");
    }
    if (expiresAt.isBefore(issuedAt)) {
      throw new IllegalArgumentException("expiresAt must be after issuedAt");
    }

    // Defensive copy of scopes
    scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
  }

  /**
   * Factory method to create an invalid token for initialization and testing.
   *
   * @return an invalid OAuth2Token instance
   */
  public static OAuth2Token invalidToken() {
    return new OAuth2Token(
        "INVALID",
        OAuth2TokenType.BEARER,
        Instant.EPOCH,
        Instant.EPOCH,
        Set.of()
    );
  }


  /**
   * Checks if the token is valid considering the given threshold and clock
   *
   * @param threshold how long before actual expiry should token be considered invalid
   * @param clock the clock to use for current time
   * @return true if token is valid and not within threshold of expiry
   */
  public boolean isValid(Duration threshold, Clock clock) {
    return tokenValue != null
        && !tokenValue.equals("INVALID")
        && clock.instant().plus(threshold).isBefore(expiresAt);
  }

  /**
   * Checks if the token is valid considering the given threshold using the system clock
   *
   * @param threshold how long before actual expiry should token be considered invalid
   * @return true if token is valid and not within threshold of expiry
   */
  public boolean isValid(Duration threshold) {
    return isValid(threshold, Clock.systemUTC());
  }

  /**
   * Gets time until token expiry using the given clock
   *
   * @param clock the clock to use for current time
   * @return Duration until token expires
   */
  public Duration timeUntilExpiry(Clock clock) {
    return Duration.between(clock.instant(), expiresAt);
  }

  /**
   * Gets time until token expiry using the system clock
   *
   * @return Duration until token expires
   */
  public Duration timeUntilExpiry() {
    return timeUntilExpiry(Clock.systemUTC());
  }

  /**
   * Returns token value with type prefix (e.g., "Bearer tokenValue")
   *
   * @return formatted token for use in Authorization header
   */
  public String getAuthorizationHeaderValue() {
    return tokenType + " " + tokenValue;
  }

  /**
   * Returns true if token has all the required scopes
   *
   * @param requiredScopes scopes to check for
   * @return true if token has all required scopes
   */
  public boolean hasScopes(Set<String> requiredScopes) {
    return scopes.containsAll(requiredScopes);
  }

  @Override
  public String toString() {
    return "OAuth2Token[tokenType=" + tokenType
        + ", issuedAt=" + issuedAt
        + ", expiresAt=" + expiresAt
        + ", scopes=" + scopes + "]";
  }
}
