package org.tokenmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Shared Keycloak container and helpers for integration tests.
 * Uses the singleton container pattern: one Keycloak instance per JVM,
 * shared across all IT classes.
 */
final class KeycloakTestSupport {

    static final KeycloakContainer KEYCLOAK;
    static final String TOKEN_ENDPOINT;
    static final OkHttpClient HTTP_CLIENT;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern AUTH_CODE_PATTERN = Pattern.compile("[?&]code=([^&]+)");

    static {
        KEYCLOAK = new KeycloakContainer()
                .withRealmImportFile("test-realm.json")
                .useTls();
        KEYCLOAK.start();
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
            if (!response.isSuccessful()) {
                throw new IOException("Failed to obtain refresh token: " + response.code()
                        + " " + response.body().string());
            }
            JsonNode node = OBJECT_MAPPER.readTree(response.body().string());
            JsonNode refreshToken = node.get("refresh_token");
            if (refreshToken == null) {
                throw new IOException("No refresh_token in response");
            }
            return refreshToken.asText();
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
}
