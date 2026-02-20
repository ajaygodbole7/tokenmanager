/*
 * Copyright 2026 ajaygodbole7
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ajaygodbole7.tokenmanager;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public enum OAuth2TokenType {
  BEARER("Bearer"),
  MAC("MAC"),
  BASIC("Basic");

  private static final Map<String, OAuth2TokenType> TOKEN_TYPE_MAP = new HashMap<>();

  static {
    for (OAuth2TokenType type : values()) {
      TOKEN_TYPE_MAP.put(type.value.toLowerCase(Locale.ROOT), type);
    }
  }

  private final String value;

  OAuth2TokenType(String value) {
    this.value = value;
  }

  public static OAuth2TokenType fromString(String value) {
    if (value == null) {
      return BEARER;
    }
    OAuth2TokenType type = TOKEN_TYPE_MAP.get(value.toLowerCase(Locale.ROOT));
    return type != null ? type : BEARER;
  }

  @Override
  public String toString() {
    return value;
  }
}
