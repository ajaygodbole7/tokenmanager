package org.tokenmanager;

public class InvalidEndpointException extends RuntimeException {
  public InvalidEndpointException(String message) {
    super(message);
  }

  public InvalidEndpointException(String message, Throwable cause) {
    super(message, cause);
  }
}
