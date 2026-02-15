package org.tokenmanager;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Concurrency integration tests against a real Keycloak instance.
 * Validates refresh coalescing, concurrent load handling, and thread safety
 * with actual network I/O rather than canned mock responses.
 */
@Tag("integration")
class TokenManagerConcurrencyIT {

    @Test
    void shouldCoalesceFirstTokenFetchAcrossThreads() throws Exception {
        // 10 threads calling getToken() simultaneously all receive the same token (single network request)
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.SERVICE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            int threadCount = 10;
            Set<String> tokens = ConcurrentHashMap.newKeySet();
            List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        tokens.add(manager.getToken());
                    } catch (Exception e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("All threads should be ready").isTrue();
            go.countDown();

            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("All threads should complete")
                    .isTrue();

            assertThat(errors).as("No threads should fail").isEmpty();
            assertThat(tokens).hasSize(1);

            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldCoalesceRefreshAcrossThreads() throws Exception {
        // 10 threads triggering refresh simultaneously all receive the same new token
        MutableClock clock = new MutableClock(Instant.now());

        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.SERVICE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .refreshThreshold(Duration.ofSeconds(5))
                .clock(clock)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            String initial = manager.getToken();
            assertThat(initial).isNotNull();

            // Advance past realm's accessTokenLifespan + threshold
            clock.advance(Duration.ofSeconds(
                    KeycloakTestSupport.REALM_TOKEN_LIFETIME_SECONDS + 6));

            int threadCount = 10;
            Set<String> tokens = ConcurrentHashMap.newKeySet();
            List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        tokens.add(manager.getToken());
                    } catch (Exception e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("All threads should be ready").isTrue();
            go.countDown();

            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("All threads should complete")
                    .isTrue();

            assertThat(errors).as("No threads should fail").isEmpty();
            // All threads get the same refreshed token, different from initial
            assertThat(tokens).hasSize(1);
            assertThat(tokens.iterator().next()).isNotEqualTo(initial);

            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldHandleHeavyConcurrentLoad() throws Exception {
        // 20 threads x 5 iterations: no exceptions, all returned tokens are valid
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId(KeycloakTestSupport.SERVICE_CLIENT_ID)
                .clientSecret(KeycloakTestSupport.SERVICE_CLIENT_SECRET)
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            int threadCount = 20;
            int iterationsPerThread = 5;
            List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
            Set<String> allTokens = ConcurrentHashMap.newKeySet();
            CountDownLatch done = new CountDownLatch(threadCount);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < iterationsPerThread; j++) {
                            String token = manager.getToken();
                            allTokens.add(token);
                        }
                    } catch (Exception e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("All threads should complete")
                    .isTrue();

            assertThat(errors).isEmpty();
            assertThat(allTokens).isNotEmpty()
                    .allSatisfy(token -> assertThat(token).isNotNull().isNotEmpty());

            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
