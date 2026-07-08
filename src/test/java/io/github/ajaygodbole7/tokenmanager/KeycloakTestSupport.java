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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Shared Keycloak container and helpers for integration tests.
 * Uses the singleton container pattern: one Keycloak instance per JVM,
 * shared across all IT classes.
 *
 * <h3>Test realm contract (test-realm-realm.json)</h3>
 * <pre>
 * Realm: test-realm
 * Access token lifespan: 60 seconds
 *
 * Clients:
 *   test-service-client / test-service-secret
 *     - client_credentials (serviceAccountsEnabled)
 *     - Accepts CLIENT_SECRET_POST and CLIENT_SECRET_BASIC
 *   test-password-client / test-password-secret
 *     - password grant (directAccessGrantsEnabled)
 *   test-authcode-client / test-authcode-secret
 *     - authorization_code (standardFlowEnabled)
 *     - redirect: http://localhost/callback
 *
 * Users:
 *   testuser / testpass
 * </pre>
 */
final class KeycloakTestSupport {

    // ---- Realm contract constants (keep in sync with test-realm-realm.json) ----
    static final int REALM_TOKEN_LIFETIME_SECONDS = 60;
    static final String SERVICE_CLIENT_ID = "test-service-client";
    static final String SERVICE_CLIENT_SECRET = "test-service-secret";
    static final String PASSWORD_CLIENT_ID = "test-password-client";
    static final String PASSWORD_CLIENT_SECRET = "test-password-secret";
    static final String AUTHCODE_CLIENT_ID = "test-authcode-client";
    static final String AUTHCODE_CLIENT_SECRET = "test-authcode-secret";
    static final String AUTHCODE_REDIRECT_URI = "http://localhost/callback";
    static final String TEST_USERNAME = "testuser";
    static final String TEST_PASSWORD = "testpass";

    // ---- Container and derived state ----
    static final KeycloakContainer KEYCLOAK;
    static final String TOKEN_ENDPOINT;
    static final OkHttpClient HTTP_CLIENT;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern AUTH_CODE_PATTERN = Pattern.compile("[?&]code=([^&]+)");

    static {
        try {
            KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.5")
                    .withRealmImportFile("test-realm-realm.json")
                    .useTls();
            KEYCLOAK.start();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Keycloak container failed to start. Is Docker running?", e);
        }
        TOKEN_ENDPOINT = KEYCLOAK.getAuthServerUrl()
                + "/realms/test-realm/protocol/openid-connect/token";
        HTTP_CLIENT = createTrustAllClient(Duration.ofSeconds(15));
    }

    private KeycloakTestSupport() {}

    static OkHttpClient createTrustAllClient(Duration timeout) {
        X509TrustManager trustAllManager = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllManager}, new SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), trustAllManager)
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(timeout)
                    .readTimeout(timeout)
                    .writeTimeout(timeout)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Failed to create trust-all OkHttpClient", e);
        }
    }

    /**
     * Programmatically obtains an authorization code by simulating the browser login flow.
     * GETs the Keycloak authorization URL, POSTs credentials to the login form,
     * and captures the redirect {@code ?code=} parameter.
     */
    static String obtainAuthorizationCode(String clientId, String redirectUri,
            String username, String password) throws IOException {
        OkHttpClient noRedirectClient = HTTP_CLIENT.newBuilder()
                .followRedirects(false)
                .cookieJar(new InMemoryCookieJar())
                .build();

        String authUrl = KEYCLOAK.getAuthServerUrl()
                + "/realms/test-realm/protocol/openid-connect/auth"
                + "?client_id=" + clientId
                + "&redirect_uri=" + redirectUri
                + "&response_type=code"
                + "&scope=openid";

        // Step 1: GET the login page to obtain the session and action URL
        Request loginPageRequest = new Request.Builder().url(authUrl).build();
        String loginFormAction;
        try (Response loginPageResponse = noRedirectClient.newCall(loginPageRequest).execute()) {
            // Keycloak may redirect to the login form
            if (loginPageResponse.isRedirect()) {
                String redirectUrl = loginPageResponse.header("Location");
                try (Response followedResponse = noRedirectClient.newCall(
                        new Request.Builder().url(redirectUrl).build()).execute()) {
                    loginFormAction = extractFormAction(followedResponse.body().string());
                }
            } else {
                loginFormAction = extractFormAction(loginPageResponse.body().string());
            }
        }

        // Step 2: POST credentials to the login form action URL
        FormBody credentialsBody = new FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .build();

        Request credentialsRequest = new Request.Builder()
                .url(loginFormAction)
                .post(credentialsBody)
                .build();

        try (Response credentialsResponse = noRedirectClient.newCall(credentialsRequest).execute()) {
            String location = credentialsResponse.header("Location");
            if (location == null) {
                throw new IOException("No redirect after login — check credentials or realm config");
            }
            Matcher matcher = AUTH_CODE_PATTERN.matcher(location);
            if (!matcher.find()) {
                throw new IOException("No authorization code in redirect: " + location);
            }
            return matcher.group(1);
        }
    }

    /**
     * Obtains a refresh token out-of-band by performing a password grant
     * directly against the token endpoint.
     */
    static String obtainRefreshToken(String clientId, String clientSecret,
            String username, String password) throws IOException {
        FormBody body = new FormBody.Builder()
                .add("grant_type", "password")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("username", username)
                .add("password", password)
                .build();

        Request request = new Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(body)
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            String bodyString = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Failed to obtain refresh token: " + response.code()
                        + " " + bodyString);
            }
            JsonNode node = OBJECT_MAPPER.readTree(bodyString);
            JsonNode refreshToken = node.get("refresh_token");
            if (refreshToken == null) {
                throw new IOException("No refresh_token in response");
            }
            return refreshToken.asText();
        }
    }

    /**
     * Freezes the Keycloak container using cgroups (docker pause).
     * Existing TCP connections will hang until their read timeout expires.
     * Always call {@link #unpauseKeycloak()} in a finally block.
     */
    static void pauseKeycloak() throws IOException, InterruptedException {
        int exit = new ProcessBuilder("docker", "pause", KEYCLOAK.getContainerId())
                .redirectErrorStream(true).start().waitFor();
        if (exit != 0) {
            throw new IOException("docker pause failed with exit code " + exit);
        }
    }

    /**
     * Unfreezes the Keycloak container after a {@link #pauseKeycloak()} call.
     */
    static void unpauseKeycloak() throws IOException, InterruptedException {
        int exit = new ProcessBuilder("docker", "unpause", KEYCLOAK.getContainerId())
                .redirectErrorStream(true).start().waitFor();
        if (exit != 0) {
            throw new IOException("docker unpause failed with exit code " + exit);
        }
    }

    private static String extractFormAction(String html) throws IOException {
        // Extract the action URL from the Keycloak login form
        Pattern actionPattern = Pattern.compile("action=\"([^\"]+)\"");
        Matcher matcher = actionPattern.matcher(html);
        if (!matcher.find()) {
            throw new IOException("Could not find form action in login page");
        }
        // Keycloak HTML-encodes ampersands in the action URL
        return matcher.group(1).replace("&amp;", "&");
    }

    private static class InMemoryCookieJar implements CookieJar {
        private final List<Cookie> cookies = new ArrayList<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            this.cookies.addAll(cookies);
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            return cookies;
        }
    }
}
