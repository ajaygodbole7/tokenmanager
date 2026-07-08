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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.mockwebserver.MockResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards against secrets leaking into log output. Distinct from
 * {@code exceptionMessagesShouldNotContainSecrets} in
 * {@link TokenManagerConfigAndSecurityTest}, which only checks exception messages —
 * this captures every log line the manager's logger emits (info/warn/error) and checks
 * it directly, independent of whether the same failure also throws.
 */
class TokenManagerLogLeakTest extends AbstractMockServerTest {

  private static final String SECRET_CANARY = "super-secret-log-leak-canary-9f3a";
  private static final String PASSWORD_CANARY = "hunter2-log-leak-canary-7b1c";
  private static final String ASSERTION_CANARY = "assertion-log-leak-canary-2e6d.payload.sig";

  private CollectingAppender appender;
  private LoggerConfig loggerConfig;
  private LoggerContext loggerContext;

  @BeforeEach
  void attachAppender() {
    loggerContext = (LoggerContext) LogManager.getContext(false);
    Configuration config = loggerContext.getConfiguration();
    loggerConfig = config.getLoggerConfig(OAuth2TokenManager.class.getName());
    appender = new CollectingAppender();
    appender.start();
    config.addAppender(appender);
    loggerConfig.addAppender(appender, null, null);
    loggerContext.updateLoggers();
  }

  @AfterEach
  void detachAppender() {
    loggerConfig.removeAppender(appender.getName());
    loggerContext.updateLoggers();
    appender.stop();
  }

  private void assertNoLeak(String canary) {
    assertThat(appender.messages())
        .as("captured log lines should never contain the configured secret")
        .noneMatch(m -> m.contains(canary));
  }

  @Test
  void successfulFetchDoesNotLogSecret() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"access_token": "t", "token_type": "Bearer", "expires_in": 3600}
            """));

    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("log-leak-" + UUID.randomUUID())
        .clientSecret(SECRET_CANARY)
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(config)) {
      manager.getToken();
    }
    assertNoLeak(SECRET_CANARY);
  }

  @Test
  void invalidClientFailureDoesNotLogSecret() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(401)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "invalid_client", "error_description": "bad credentials"}
            """));

    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("log-leak-" + UUID.randomUUID())
        .clientSecret(SECRET_CANARY)
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(config)) {
      assertThatThrownBy(manager::getToken).isInstanceOf(InvalidCredentialsException.class);
    }
    assertNoLeak(SECRET_CANARY);
  }

  @Test
  void malformedJsonResponseDoesNotLogSecret() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("{not valid json"));

    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("log-leak-" + UUID.randomUUID())
        .clientSecret(SECRET_CANARY)
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(config)) {
      assertThatThrownBy(manager::getToken).isInstanceOf(ServiceUnavailableException.class);
    }
    assertNoLeak(SECRET_CANARY);
  }

  @Test
  void rateLimitedResponseDoesNotLogSecret() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(429)
        .addHeader("Retry-After", "30")
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("{\"error\": \"rate_limited\"}"));

    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("log-leak-" + UUID.randomUUID())
        .clientSecret(SECRET_CANARY)
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(config)) {
      assertThatThrownBy(manager::getToken).isInstanceOf(RateLimitedException.class);
    }
    assertNoLeak(SECRET_CANARY);
  }

  @Test
  void clientSecretBasicAuthDoesNotLogSecret() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"access_token": "t", "token_type": "Bearer", "expires_in": 3600}
            """));

    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("log-leak-" + UUID.randomUUID())
        .clientSecret(SECRET_CANARY)
        .clientAuthMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(config)) {
      manager.getToken();
    }
    assertNoLeak(SECRET_CANARY);
  }

  @Test
  void passwordGrantFailureDoesNotLogPasswordOrSecret() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(401)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"error": "invalid_grant", "error_description": "bad credentials"}
            """));

    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("log-leak-" + UUID.randomUUID())
        .clientSecret(SECRET_CANARY)
        .grantType(OAuth2GrantType.PASSWORD)
        .username("log-leak-user")
        .password(PASSWORD_CANARY)
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(config)) {
      assertThatThrownBy(manager::getToken).isInstanceOf(InvalidCredentialsException.class);
    }
    assertNoLeak(SECRET_CANARY);
    assertNoLeak(PASSWORD_CANARY);
  }

  @Test
  void jwtBearerAssertionIsNeverLogged() {
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"access_token": "t", "token_type": "Bearer", "expires_in": 3600}
            """));

    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("log-leak-" + UUID.randomUUID())
        .clientSecret(SECRET_CANARY)
        .grantType(OAuth2GrantType.JWT_BEARER)
        .assertion(ASSERTION_CANARY)
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    try (OAuth2TokenManager manager = new OAuth2TokenManager(config)) {
      manager.getToken();
    }
    assertNoLeak(ASSERTION_CANARY);
  }

  /** Collects formatted log messages (plus any thrown cause) for assertion. */
  private static final class CollectingAppender extends AbstractAppender {
    private final List<String> messages = new CopyOnWriteArrayList<>();

    CollectingAppender() {
      super("log-leak-test-appender", null, PatternLayout.createDefaultLayout(), true, null);
    }

    @Override
    public void append(LogEvent event) {
      StringBuilder sb = new StringBuilder(event.getMessage().getFormattedMessage());
      Throwable thrown = event.getThrown();
      if (thrown != null) {
        sb.append(' ').append(thrown);
      }
      messages.add(sb.toString());
    }

    List<String> messages() {
      return messages;
    }
  }
}
