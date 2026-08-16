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

/**
 * Root of the sealed exception hierarchy thrown by {@link TokenProvider} operations.
 *
 * <p><b>Stability commitment:</b> the permitted set of subclasses —
 * {@link InvalidCredentialsException}, {@link InvalidConfigurationException},
 * {@link InvalidEndpointException}, {@link RateLimitedException},
 * {@link ServiceUnavailableException} — is considered closed and stable. Callers
 * are encouraged to switch exhaustively over it; adding a subclass would be a
 * source-breaking change for every such switch and will not happen within a
 * major version.
 */
public sealed class TokenException extends RuntimeException
    permits InvalidCredentialsException, InvalidConfigurationException,
            InvalidEndpointException, RateLimitedException,
            ServiceUnavailableException {

  public TokenException(String message) {
    super(message);
  }

  public TokenException(String message, Throwable cause) {
    super(message, cause);
  }
}
