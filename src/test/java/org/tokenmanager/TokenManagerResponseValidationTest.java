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
package org.tokenmanager;

import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenManagerResponseValidationTest extends AbstractMockServerTest {

  @Test
  void shouldLimitResponseBodySize() throws Exception {
    // Build a 128 KB body — well over the 64 KB limit.
    // The truncated body will be invalid JSON → ServiceUnavailableException.
    String padding = "x".repeat(128 * 1024);
    String oversizedBody = "{\"access_token\": \"" + padding + "\", \"token_type\": \"bearer\", \"expires_in\": 3600}";

    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody(oversizedBody));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class);
  }


  /**
   * Tests that TokenManager properly handles malformed responses.
   *
   * When the server returns valid JSON but missing required fields
   * Then the TokenManager should:
   * - Fail with ServiceUnavailableException
   * - Not cache the invalid response
   * - Allow subsequent valid requests
   */
  @Test
  void whenMalformedResponse_thenThrowsServiceException() throws Exception {
    // Given: Server returns JSON with missing required fields
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """));  // Missing access_token field

    // When/Then: First request should fail
    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("Missing access_token in response");

    // And: Should have made exactly one request
    assertThat(mockWebServer.getRequestCount())
        .as("Should make exactly one request")
        .isEqualTo(1);

    // Given: Server now returns valid response
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {
                "access_token": "valid-token",
                "token_type": "Bearer",
                "expires_in": 3600
            }
            """));

    // When: Second request should succeed
    String token = tokenManager.getToken();

    // Then: Should get valid token
    assertThat(token).isEqualTo("valid-token");
  }

  @Test
  void shouldThrowServiceUnavailableFor2xxWithEmptyBody() throws Exception {
    // 200 with empty body is a malformed response, not an endpoint error.
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void shouldRejectZeroExpiresIn() throws Exception {
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {"access_token": "token", "token_type": "bearer", "expires_in": 0}
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("expires_in");
  }

  @Test
  void shouldRejectNegativeExpiresIn() throws Exception {
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {"access_token": "token", "token_type": "bearer", "expires_in": -1}
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("expires_in");
  }

  @Test
  void shouldRejectStringExpiresIn() throws Exception {
    mockWebServer.enqueue(new MockResponse()
                              .setResponseCode(200)
                              .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
                              .setBody("""
            {"access_token": "token", "token_type": "bearer", "expires_in": "not_a_number"}
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("expires_in");
  }

  @Test
  void shouldRejectExpiresInOverflow() throws Exception {
    // Server returns absurdly large expires_in → ServiceUnavailableException
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"access_token": "token", "token_type": "bearer", "expires_in": 999999999}
            """));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("expires_in")
        .hasMessageContaining("exceeds maximum");
  }

  @Test
  void shouldAcceptExpiresInAtMaximum() throws Exception {
    // 365 days is the upper bound — should be accepted
    long maxExpiresIn = 86400L * 365;
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"access_token": "long-lived", "token_type": "bearer", "expires_in": %d}
            """.formatted(maxExpiresIn)));

    String token = tokenManager.getToken();
    assertThat(token).isEqualTo("long-lived");
  }

  @Test
  void shouldRejectExpiresInJustOverMaximum() throws Exception {
    // One second over the maximum → rejected
    long overMax = 86400L * 365 + 1;
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"access_token": "token", "token_type": "bearer", "expires_in": %d}
            """.formatted(overMax)));

    assertThatThrownBy(() -> tokenManager.getToken())
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("expires_in")
        .hasMessageContaining("exceeds maximum");
  }

  @Test
  void shouldDefaultToBearerWhenTokenTypeFieldMissing() throws Exception {
    // Response has no token_type key at all — should default to Bearer
    mockWebServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody("""
            {"access_token": "no-type-token", "expires_in": 3600}
            """));

    String token = tokenManager.getToken();
    assertThat(token).isEqualTo("no-type-token");
  }
}
