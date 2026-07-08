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

public enum OAuth2GrantType {
  CLIENT_CREDENTIALS("client_credentials"),

  /**
   * Resource Owner Password Credentials (ROPC) grant. This is a single-exchange grant type:
   * the initial token request succeeds, but subsequent refreshes replay the same username
   * and password. Use {@code CLIENT_CREDENTIALS} or {@code JWT_BEARER} for long-lived
   * machine-to-machine token management.
   */
  PASSWORD("password"),

  /**
   * Authorization Code grant. This is a single-exchange grant type: authorization codes
   * are single-use and cannot be replayed. Subsequent refresh attempts will fail because
   * the original code has already been consumed by the authorization server.
   */
  AUTHORIZATION_CODE("authorization_code"),

  /**
   * Refresh Token grant. This is a single-exchange grant type: refresh tokens may be
   * rotated by the authorization server on each use, making the original token invalid
   * for subsequent requests.
   */
  REFRESH_TOKEN("refresh_token"),

  IMPLICIT("implicit"),
  JWT_BEARER("urn:ietf:params:oauth:grant-type:jwt-bearer");

  private final String value;

  OAuth2GrantType(String value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return value;
  }
}
