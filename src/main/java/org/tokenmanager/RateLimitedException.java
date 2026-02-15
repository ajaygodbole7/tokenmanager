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
