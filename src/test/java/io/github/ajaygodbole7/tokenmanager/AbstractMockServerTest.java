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

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * Shared test infrastructure for TokenManager unit tests.
 * Sets up a TLS-enabled MockWebServer with per-test certificate chains,
 * an OkHttpClient that trusts the test CA, and a default TokenConfig/TokenManager.
 */
abstract class AbstractMockServerTest {

  static final String TOKEN_ENDPOINT = "/oauth/token";
  static final String CONTENT_TYPE_HEADER = "Content-Type";
  static final String CONTENT_TYPE_JSON = "application/json";
  static final String CONTENT_TYPE_FORM_URLENCODED = "application/x-www-form-urlencoded";
  static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
  static final Duration REFRESH_THRESHOLD = Duration.ofSeconds(30);

  MockWebServer mockWebServer;
  OkHttpClient httpClient;
  TokenConfig tokenConfig;
  OAuth2TokenManager tokenManager;

  @BeforeEach
  void setUp() throws IOException {
    HeldCertificate rootCertificate = new HeldCertificate.Builder()
        .certificateAuthority(1)
        .build();

    HeldCertificate serverCertificate = new HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .signedBy(rootCertificate)
        .build();

    HandshakeCertificates serverCerts = new HandshakeCertificates.Builder()
        .heldCertificate(serverCertificate)
        .build();

    HandshakeCertificates clientCerts = new HandshakeCertificates.Builder()
        .addTrustedCertificate(rootCertificate.certificate())
        .build();

    mockWebServer = new MockWebServer();
    mockWebServer.useHttps(serverCerts.sslSocketFactory(), false);
    mockWebServer.start();

    httpClient = new OkHttpClient.Builder()
        .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager())
        .hostnameVerifier((hostname, session) -> true)
        .connectTimeout(HTTP_TIMEOUT)
        .readTimeout(HTTP_TIMEOUT)
        .writeTimeout(HTTP_TIMEOUT)
        .retryOnConnectionFailure(false)
        .build();

    tokenConfig = TokenConfig.builder()
        .tokenEndpoint(mockWebServer.url(TOKEN_ENDPOINT).toString())
        .clientId("test-client-" + UUID.randomUUID())
        .clientSecret("test-secret")
        .httpTimeout(HTTP_TIMEOUT)
        .refreshThreshold(REFRESH_THRESHOLD)
        .httpClient(httpClient)
        .build();

    tokenManager = new OAuth2TokenManager(tokenConfig);
  }

  @AfterEach
  void tearDown() throws IOException {
    if (tokenManager != null) {
      tokenManager.close();
    }
    if (mockWebServer != null) {
      mockWebServer.shutdown();
    }
    if (httpClient != null) {
      httpClient.dispatcher().executorService().shutdown();
      httpClient.connectionPool().evictAll();
    }
  }

  MockResponse successResponse(String tokenValue, int expiresIn) {
    return new MockResponse()
        .setResponseCode(200)
        .addHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_JSON)
        .setBody(String.format("""
            {"access_token": "%s", "token_type": "bearer", "expires_in": %d}
            """, tokenValue, expiresIn));
  }
}
