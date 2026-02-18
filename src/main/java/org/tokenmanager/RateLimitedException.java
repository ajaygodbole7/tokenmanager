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

import java.time.Duration;

public final class RateLimitedException extends TokenException {
  private final Duration retryAfter;

  public RateLimitedException(String message, Duration retryAfter) {
    super(message);
    this.retryAfter = retryAfter;
  }

  /**
   * Returns the server-suggested wait time before retrying, or null if the
   * server did not include a Retry-After header.
   */
  public Duration getRetryAfter() {
    return retryAfter;
  }
}
