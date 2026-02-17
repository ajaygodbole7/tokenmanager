package org.tokenmanager;

import java.util.concurrent.CompletableFuture;

/**
 * Provides OAuth2 access tokens with automatic caching and refresh.
 * Callers use {@link #getToken()} for blocking access or
 * {@link #getTokenAsync()} for non-blocking access.
 *
 * <p>Implementations manage token lifecycle (caching, refresh, fault tolerance)
 * internally. This interface exists to decouple callers from the concrete
 * {@link OAuth2TokenManager} and enable unit testing with mocks.
 */
public interface TokenProvider extends AutoCloseable {

    /**
     * Returns a valid access token, refreshing if necessary.
     * Blocks until the token is available.
     *
     * @return a valid access token string
     * @throws TokenException if the token cannot be obtained
     */
    String getToken();

    /**
     * Returns a future that completes with a valid access token, refreshing if necessary.
     * The future completes exceptionally with a {@link TokenException} subclass on failure.
     *
     * <p>Cache hits return an already-completed future. Refresh operations chain onto
     * the internal refresh future without blocking the calling thread.
     *
     * @return a future completing with a valid access token string
     */
    CompletableFuture<String> getTokenAsync();

    @Override
    void close();
}
