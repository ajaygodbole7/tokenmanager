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
