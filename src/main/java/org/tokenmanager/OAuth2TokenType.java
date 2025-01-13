package org.tokenmanager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public enum OAuth2TokenType {
  BEARER("Bearer"),
  MAC("MAC"),
  BASIC("Basic");

  private static final Map<String, OAuth2TokenType> TOKEN_TYPE_MAP = new HashMap<>();

  static {
    for (OAuth2TokenType type : values()) {
      TOKEN_TYPE_MAP.put(type.value, type);
    }
  }

  private final String value;

  OAuth2TokenType(String value) {
    this.value = value;
  }

  public static OAuth2TokenType fromString(String value) {
    if (value == null) {
      return BEARER; // default to BEARER if not specified
    }
    OAuth2TokenType type = TOKEN_TYPE_MAP.get(value);
    if (type == null) {
      throw new IllegalArgumentException(
          String.format(
              "Unknown token type: %s. Valid values are: %s", value, Arrays.toString(values())));
    }
    return type;
  }

  @Override
  public String toString() {
    return value;
  }
}
