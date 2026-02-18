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
package org.tokenmanager;

/**
 * OAuth2 client authentication method for the token endpoint.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3.1">RFC 6749 Section 2.3.1</a>
 */
public enum ClientAuthMethod {

  /** Send client_id and client_secret as form body parameters. */
  CLIENT_SECRET_POST,

  /** Send credentials as an HTTP Basic Authorization header. */
  CLIENT_SECRET_BASIC
}
