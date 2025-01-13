package org.tokenmanager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public enum OAuth2GrantType {
  CLIENT_CREDENTIALS("client_credentials"),
  PASSWORD("password"),
  AUTHORIZATION_CODE("authorization_code"),
  REFRESH_TOKEN("refresh_token"),
  IMPLICIT("implicit"),
  JWT_BEARER("urn:ietf:params:oauth:grant-type:jwt-bearer");

  private static final Map<String, OAuth2GrantType> GRANT_MAP = new HashMap<>();

  static {
    for (OAuth2GrantType type : values()) {
      GRANT_MAP.put(type.value, type);
    }
  }

  private final String value;

  OAuth2GrantType(String value) {
    this.value = value;
  }

  public static OAuth2GrantType fromString(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Grant type cannot be null");
    }
    OAuth2GrantType type = GRANT_MAP.get(value);
    if (type == null) {
      throw new IllegalArgumentException(
          String.format(
              "Unknown grant type: %s. Valid values are: %s", value, Arrays.toString(values())));
    }
    return type;
  }

  @Override
  public String toString() {
    return value;
  }
}
