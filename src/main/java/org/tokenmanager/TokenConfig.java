package org.tokenmanager;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.NonNull;
import lombok.ToString;
import lombok.Value;
import okhttp3.OkHttpClient;

@Value
@Builder
@ToString(exclude = {
    "clientSecret", "password", "authorizationCode",
    "codeVerifier", "refreshToken", "assertion"
})
public class TokenConfig {

  // Timeout for individual HTTP operations
  private static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(10);
  // How long before token expiry should we refresh
  private static final Duration DEFAULT_REFRESH_THRESHOLD = Duration.ofSeconds(30);

  @NonNull String tokenEndpoint;
  @NonNull String clientId;
  @NonNull String clientSecret;

  @NonNull @Default OAuth2GrantType grantType = OAuth2GrantType.CLIENT_CREDENTIALS;

  @NonNull @Default Set<String> scope = Collections.emptySet();

  @NonNull @Default Duration refreshThreshold = DEFAULT_REFRESH_THRESHOLD;

  @NonNull @Default Duration httpTimeout = DEFAULT_HTTP_TIMEOUT;

  @NonNull @Default Clock clock = Clock.systemUTC();

  @NonNull @Default ClientAuthMethod clientAuthMethod = ClientAuthMethod.CLIENT_SECRET_POST;

  // Fields for other grant types
  String username; // For password grant
  String password;
  String authorizationCode; // For authorization_code grant
  String redirectUri;
  String codeVerifier; // PKCE (optional)
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
    URI uri = URI.create(tokenEndpoint);
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
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
