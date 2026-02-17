package org.tokenmanager;

/**
 * Provides OAuth2 access tokens with automatic caching and refresh.
 * Callers use {@link #getToken()} to obtain a valid token for API requests.
 *
 * <p>Implementations manage token lifecycle (caching, refresh, fault tolerance)
 * internally. This interface exists to decouple callers from the concrete
 * {@link OAuth2TokenManager} and enable unit testing with mocks.
 */
public interface TokenProvider extends AutoCloseable {

    /**
     * Returns a valid access token, refreshing if necessary.
     *
     * @return a valid access token string
     * @throws TokenException if the token cannot be obtained
     */
    String getToken();

    @Override
    void close();
}
