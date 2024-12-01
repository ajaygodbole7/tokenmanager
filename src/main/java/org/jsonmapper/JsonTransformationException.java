package org.jsonmapper;

public class JsonTransformationException extends RuntimeException {
  public JsonTransformationException(String message) {
    super(message);
  }

  public JsonTransformationException(String message, Throwable cause) {
    super(message, cause);
  }
}
