package org.tokenmanager;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.NonNull;
import lombok.Value;
import okhttp3.OkHttpClient;

@Value
@Builder
public class TokenConfig {

  // Timeout for individual HTTP operations
  private static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(10);
  // How long before token expiry should we refresh
  private static final Duration DEFAULT_REFRESH_THRESHOLD = Duration.ofSeconds(30);

  @NonNull String tokenEndpoint;
  @NonNull String clientId;
  @NonNull String clientSecret;

  @Default OAuth2GrantType grantType = OAuth2GrantType.CLIENT_CREDENTIALS;

  @Default Set<String> scope = Collections.emptySet();

  @Default Duration refreshThreshold = DEFAULT_REFRESH_THRESHOLD;

  @Default Duration httpTimeout = DEFAULT_HTTP_TIMEOUT;

  // Fields for other grant types
  String username; // For password grant
  String password;
  String authorizationCode; // For authorization_code grant
  String redirectUri;
  String codeVerifier; // PKCE
  String state;
  String refreshToken; // For refresh_token grant
  String assertion; // For JWT_BEARER

  // Optional custom HTTP client (typically for testing)
  OkHttpClient httpClient;

  public void validate() {
    if (refreshThreshold.isNegative() || refreshThreshold.isZero()) {
      throw new IllegalArgumentException("refreshThreshold must be positive");
    }
    if (httpTimeout.isNegative() || httpTimeout.isZero()) {
      throw new IllegalArgumentException("httpTimeout must be positive");
    }
    if (!tokenEndpoint.toLowerCase().startsWith("https://")) {
      throw new IllegalArgumentException("tokenEndpoint must use HTTPS");
    }

    // Validate based on grant type
    switch (grantType) {
      case PASSWORD -> {
        if (username == null || password == null) {
          throw new IllegalArgumentException("Password grant requires username and password");
        }
      }
      case AUTHORIZATION_CODE -> {
        if (authorizationCode == null || redirectUri == null) {
          throw new IllegalArgumentException(
              "Authorization code grant requires code and redirect URI");
        }
      }
      case REFRESH_TOKEN -> {
        if (refreshToken == null) {
          throw new IllegalArgumentException("Refresh token grant requires refresh token");
        }
      }
      case JWT_BEARER -> {
        if (assertion == null) {
          throw new IllegalArgumentException("JWT Bearer grant requires assertion");
        }
      }
      case IMPLICIT ->
          throw new IllegalArgumentException(
              "Implicit grant type is not supported by token endpoint");
      case CLIENT_CREDENTIALS -> {
        /* already validated by @NonNull fields */
      }
    }
  }

  String getScopeString() {
    return scope.isEmpty() ? "" : String.join(" ", scope);
  }
}
