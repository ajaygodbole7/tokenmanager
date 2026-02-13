package org.tokenmanager;

public final class InvalidEndpointException extends TokenException {
  public InvalidEndpointException(String message) {
    super(message);
  }

  public InvalidEndpointException(String message, Throwable cause) {
    super(message, cause);
  }
}
