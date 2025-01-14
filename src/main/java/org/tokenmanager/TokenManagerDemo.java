package org.tokenmanager;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TokenManagerDemo {

  public static void main(String[] args) {
    // Create token configuration
    TokenConfig config = TokenConfig.builder()
        .grantType(OAuth2GrantType.PASSWORD)
        .clientId("demo-client")
        .clientSecret("demo-secret")
        .username("user@example.com")
        .password("userPassword123")
        .tokenEndpoint("https://auth.example.com/oauth/token")
        .httpTimeout(Duration.ofSeconds(10))
        .refreshThreshold(Duration.ofSeconds(30))
        .build();

    // Create token manager instance
    try (Oauth2TokenManager tokenManager = new Oauth2TokenManager(config)) {
      // Create multiple threads that will request tokens
      Thread thread1 = new Thread(() -> getTokenAndLog(tokenManager, "Thread-1"));
      Thread thread2 = new Thread(() -> getTokenAndLog(tokenManager, "Thread-2"));
      Thread thread3 = new Thread(() -> getTokenAndLog(tokenManager, "Thread-3"));

      // Start all threads
      thread1.start();
      thread2.start();
      thread3.start();

      // Wait for all threads to complete
      thread1.join();
      thread2.join();
      thread3.join();

      // Demonstrate token refresh by waiting and requesting again
      log.info("Waiting 5 seconds before requesting token again...");
      Thread.sleep(5000);

      // Create new threads to demonstrate refresh behavior
      Thread refresh1 = new Thread(() -> getTokenAndLog(tokenManager, "Refresh-1"));
      Thread refresh2 = new Thread(() -> getTokenAndLog(tokenManager, "Refresh-2"));

      refresh1.start();
      refresh2.start();

      refresh1.join();
      refresh2.join();
    } catch (Exception e) {
      log.error("Error in token manager demo", e);
    }
  }

  private static void getTokenAndLog(Oauth2TokenManager tokenManager, String threadName) {
    try {
      String token = tokenManager.getToken();
      log.info("{} got token: {}...", threadName, maskToken(token));
    } catch (Exception e) {
      log.error("{} failed to get token", threadName, e);
    }
  }

  private static String maskToken(String token) {
    if (token == null || token.length() <= 8) {
      return token;
    }
    return token.substring(0, 8) + "...";
  }
}
