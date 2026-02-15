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

/**
 * Concurrency integration tests against a real Keycloak instance.
 * Validates refresh coalescing, concurrent load handling, and thread safety
 * with actual network I/O rather than canned mock responses.
 */
class TokenManagerConcurrencyIT {

    @org.junit.jupiter.api.Test
    void shouldCoalesceFirstTokenFetchAcrossThreads() throws Exception {
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId("test-service-client")
                .clientSecret("test-service-secret")
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            int threadCount = 10;
            Set<String> tokens = ConcurrentHashMap.newKeySet();
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
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("All threads should complete")
                    .isTrue();

            assertThat(tokens).hasSize(1);

            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @org.junit.jupiter.api.Test
    void shouldCoalesceRefreshAcrossThreads() throws Exception {
        MutableClock clock = new MutableClock(Instant.now());

        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId("test-service-client")
                .clientSecret("test-service-secret")
                .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
                .refreshThreshold(Duration.ofSeconds(5))
                .clock(clock)
                .httpClient(KeycloakTestSupport.HTTP_CLIENT)
                .build();

        try (var manager = new OAuth2TokenManager(config)) {
            String initial = manager.getToken();
            assertThat(initial).isNotNull();

            // Advance past the 60s accessTokenLifespan + 5s threshold
            clock.advance(Duration.ofSeconds(66));

            int threadCount = 10;
            Set<String> tokens = ConcurrentHashMap.newKeySet();
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
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("All threads should complete")
                    .isTrue();

            // All threads get the same refreshed token, different from initial
            assertThat(tokens).hasSize(1);
            assertThat(tokens.iterator().next()).isNotEqualTo(initial);

            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @org.junit.jupiter.api.Test
    void shouldHandleHeavyConcurrentLoad() throws Exception {
        TokenConfig config = TokenConfig.builder()
                .tokenEndpoint(KeycloakTestSupport.TOKEN_ENDPOINT)
                .clientId("test-service-client")
                .clientSecret("test-service-secret")
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
