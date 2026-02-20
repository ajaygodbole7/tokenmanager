/*
 * Copyright 2026 ajaygodbole7
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ajaygodbole7.tokenmanager;

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

    /**
     * Invalidates the cached token, forcing the next {@link #getToken()} or
     * {@link #getTokenAsync()} call to fetch a fresh token from the server.
     *
     * <p>Use this when a downstream service rejects the token (e.g., 401 after
     * server-side revocation) and the caller needs to obtain a new one immediately
     * rather than waiting for the refresh threshold to expire.
     *
     * <p>If a refresh is already in progress, its result will become the new cached
     * token. Callers who need a guaranteed new server exchange should wait for the
     * current {@link #getToken()} call to complete, then call {@code invalidate()}
     * followed by {@link #getToken()}.
     *
     * <p>The default implementation is a no-op for implementations that do not
     * support invalidation.
     */
    default void invalidate() {
        // No-op by default.
    }

    @Override
    void close();
}
