package org.tokenmanager;

public final class InvalidCredentialsException extends TokenException {
  public InvalidCredentialsException(String message) {
    super(message);
  }

  public InvalidCredentialsException(String message, Throwable cause) {
    super(message, cause);
  }
}
