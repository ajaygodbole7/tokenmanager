package org.tokenmanager;

public final class RateLimitedException extends TokenException {
  public RateLimitedException(String message) {
    super(message);
  }

  public RateLimitedException(String message, Throwable cause) {
    super(message, cause);
  }
}
