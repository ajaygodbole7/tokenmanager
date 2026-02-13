package org.tokenmanager;

public sealed class TokenException extends RuntimeException
    permits InvalidCredentialsException, InvalidConfigurationException,
            InvalidEndpointException, ServiceUnavailableException {

  public TokenException(String message) {
    super(message);
  }

  public TokenException(String message, Throwable cause) {
    super(message, cause);
  }
}
