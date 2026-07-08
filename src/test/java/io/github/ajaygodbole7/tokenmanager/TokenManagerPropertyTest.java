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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Property-based tests for parsing/validation helpers that run on the
 * response-handling hot path against a server that may be malicious,
 * misconfigured, or simply non-compliant. These must never throw — a parsing
 * exception here would surface as an unclassified failure instead of the
 * documented {@link TokenException} hierarchy.
 */
class TokenManagerPropertyTest {

  private static OAuth2TokenManager newManager(
      int maxRetryAttempts, Duration httpTimeout, Duration initialRetryDelay) {
    TokenConfig config = TokenConfig.builder()
        .tokenEndpoint("https://auth.example.invalid/token")
        .clientId("property-test-client")
        .clientSecret("property-test-secret")
        .maxRetryAttempts(maxRetryAttempts)
        .httpTimeout(httpTimeout)
        .initialRetryDelay(initialRetryDelay)
        .build();
    return new OAuth2TokenManager(config);
  }

  private static Response responseWithHeader(String name, String value) {
    Request request = new Request.Builder().url("https://auth.example.invalid/token").build();
    return new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(429)
        .message("Rate Limited")
        .header(name, value)
        .build();
  }

  // --- parseRetryAfter: never throws, result always null or within [0, 24h] ---

  @Provide
  Arbitrary<String> retryAfterHeaderValues() {
    Arbitrary<String> numeric = Arbitraries.longs().map(String::valueOf);
    Arbitrary<String> knownDates = Arbitraries.of(
        "Fri, 31 Dec 1999 23:59:59 GMT",     // valid RFC 1123
        "Sunday, 06-Nov-94 08:49:37 GMT",    // valid RFC 850
        "Sun Nov  6 08:49:37 1994",          // valid asctime
        "Not, 99 Xxx 9999 99:99:99 GMT",     // malformed date
        "9999999999999999999999999999999999" // numeric-looking overflow
    );
    // Printable ASCII only — header value validity (control-char rejection) is
    // OkHttp's concern, not the parser's; this property targets parseRetryAfter.
    Arbitrary<String> garbage = Arbitraries.strings()
        .withCharRange(' ', '~')
        .ofMinLength(1)
        .ofMaxLength(40);
    return Arbitraries.oneOf(numeric, knownDates, garbage);
  }

  @Property(tries = 300)
  void parseRetryAfterNeverThrowsAndStaysInBounds(
      @ForAll("retryAfterHeaderValues") String headerValue) {
    OAuth2TokenManager manager = newManager(3, Duration.ofSeconds(10), Duration.ofSeconds(1));
    try {
      Duration result = manager.parseRetryAfter(responseWithHeader("Retry-After", headerValue));
      if (result != null) {
        assertThat(result.isNegative()).isFalse();
        assertThat(result.compareTo(Duration.ofHours(24))).isLessThanOrEqualTo(0);
      }
    } finally {
      manager.close();
    }
  }

  @Example
  void parseRetryAfterReturnsNullWhenHeaderAbsent() {
    OAuth2TokenManager manager = newManager(3, Duration.ofSeconds(10), Duration.ofSeconds(1));
    try {
      Request request = new Request.Builder().url("https://auth.example.invalid/token").build();
      Response response = new Response.Builder()
          .request(request)
          .protocol(Protocol.HTTP_1_1)
          .code(429)
          .message("Rate Limited")
          .build();
      assertThat(manager.parseRetryAfter(response)).isNull();
    } finally {
      manager.close();
    }
  }

  // --- parseScopes: never throws, never yields a blank element ---

  @Provide
  Arbitrary<String> scopeStrings() {
    Arbitrary<String> words = Arbitraries.of("read", "write", "admin", "openid", "profile");
    Arbitrary<String> repeatedTokens = Combinators.combine(
        Arbitraries.integers().between(1, 5), words)
        .as((count, word) -> String.join(" ", java.util.Collections.nCopies(count, word)));
    Arbitrary<String> whitespaceHeavy = Arbitraries.of(
        "  read   write  ", "\tread\nwrite\r", "", "   ", " read");
    Arbitrary<String> garbage = Arbitraries.strings()
        .withCharRange(' ', '~')
        .ofMinLength(0)
        .ofMaxLength(60);
    return Arbitraries.oneOf(words, repeatedTokens, whitespaceHeavy, garbage);
  }

  @Property
  void parseScopesNeverThrowsAndNeverProducesBlankElement(
      @ForAll("scopeStrings") String raw) {
    java.util.Set<String> scopes = OAuth2TokenManager.parseScopes(raw);
    assertThat(scopes).noneMatch(String::isBlank);
  }

  @Example
  void parseScopesReturnsEmptySetForNullOrBlank() {
    assertThat(OAuth2TokenManager.parseScopes(null)).isEmpty();
    assertThat(OAuth2TokenManager.parseScopes("")).isEmpty();
    assertThat(OAuth2TokenManager.parseScopes("   ")).isEmpty();
  }

  // --- extractExpiresIn: never throws for any JsonNode shape ---

  @Provide
  Arbitrary<JsonNode> expiresInNodes() {
    Arbitrary<JsonNode> longNodes = Arbitraries.longs()
        .map(v -> (JsonNode) JsonNodeFactory.instance.numberNode(v));
    Arbitrary<JsonNode> doubleNodes = Arbitraries.oneOf(
            Arbitraries.doubles(),
            Arbitraries.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
        .map(v -> (JsonNode) JsonNodeFactory.instance.numberNode(v));
    Arbitrary<JsonNode> textNodes = Arbitraries.of(
            "3600", "-1", "0", "999999999999999999999999", "3600.5", "abc", "",
            " 3600 ", "NaN", "+3600")
        .map(s -> (JsonNode) JsonNodeFactory.instance.textNode(s));
    Arbitrary<JsonNode> boolNodes = Arbitraries.of(true, false)
        .map(b -> (JsonNode) JsonNodeFactory.instance.booleanNode(b));
    Arbitrary<JsonNode> structuralNodes = Arbitraries.of(
        JsonNodeFactory.instance.nullNode(),
        JsonNodeFactory.instance.arrayNode(),
        JsonNodeFactory.instance.objectNode());
    return Arbitraries.oneOf(longNodes, doubleNodes, textNodes, boolNodes, structuralNodes);
  }

  @Property
  void extractExpiresInNeverThrows(@ForAll("expiresInNodes") JsonNode node) {
    // No throw is the property; a non-null result must be a real Long (implied
    // by the method signature — this call succeeding at all is the assertion).
    Long result = OAuth2TokenManager.extractExpiresIn(node);
    assertThat(result == null || result instanceof Long).isTrue();
  }

  @Example
  void extractExpiresInReturnsNullForNullArgument() {
    assertThat(OAuth2TokenManager.extractExpiresIn(null)).isNull();
  }

  // --- computeOverallTimeout: always positive within the valid config range ---
  // Direct regression test for the overflow bug: before maxRetryAttempts was
  // capped at 10 in TokenConfig.validate(), values around ~55+ made the
  // exponential backoff sum overflow a long, producing a negative timeout that
  // failed getToken() instantly regardless of server health.

  @Property(tries = 200)
  void computeOverallTimeoutIsAlwaysPositiveForValidConfig(
      @ForAll @IntRange(min = 1, max = 10) int maxAttempts,
      @ForAll @LongRange(min = 1000, max = 60000) long httpTimeoutMs,
      @ForAll @LongRange(min = 100, max = 10000) long initialRetryDelayMs) {
    OAuth2TokenManager manager = newManager(
        maxAttempts, Duration.ofMillis(httpTimeoutMs), Duration.ofMillis(initialRetryDelayMs));
    try {
      Duration result = manager.computeOverallTimeout();
      assertThat(result).isNotNull();
      assertThat(result.isNegative()).isFalse();
      assertThat(result.isZero()).isFalse();
    } finally {
      manager.close();
    }
  }
}
