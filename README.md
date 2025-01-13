# OAuth2 Token Manager

## Overview
OAuth2 Token Manager is a Java library that handles the complete lifecycle of OAuth2 tokens in production environments. It provides a thread-safe, resilient solution for managing access tokens with built-in support for caching, automatic refresh, and fault tolerance.

## Why is the Token Manager needed?

OAuth2 token management in production applications presents several challenges:
- Tokens expire and need periodic refresh
- Multiple threads might attempt simultaneous refreshes
- Network issues and service outages need graceful handling
- Frequent token refreshes can impact performance

The Token Manager solves these challenges with a production-ready solution that handles token lifecycle, concurrency, and error recovery automatically.

## Key Features

- **Token Caching**: Caches valid tokens in memory with configurable refresh thresholds
- **Concurrency Management**: Thread-safe implementation preventing duplicate refresh attempts
- **Resilience**: Circuit breaker pattern and exponential backoff retry mechanism
- **Error Handling**: Comprehensive OAuth2 error classification and status code mapping
- **Monitoring**: Detailed logging and metrics for service health

## Sample Invocation

### Basic Usage

```java
// Create configuration
TokenConfig config = TokenConfig.builder()
    .clientId("your-client-id")
    .clientSecret("your-client-secret")
    .tokenEndpoint("https://auth.example.com/token")
    .grantType(OAuth2GrantType.CLIENT_CREDENTIALS)
    .build();

// Initialize token manager
try (Oauth2TokenManager tokenManager = new Oauth2TokenManager(config)) {
    // Get a valid token
    String token = tokenManager.getToken();
    
    // Use token for API calls
    // Token manager will automatically refresh when needed
}
```

### Advanced Configuration

```java
TokenConfig config = TokenConfig.builder()
    .clientId("your-client-id")
    .clientSecret("your-client-secret")
    .tokenEndpoint("https://auth.example.com/token")
    .grantType(OAuth2GrantType.PASSWORD)
    .username("user@example.com")
    .password("user-password")
    .httpTimeout(Duration.ofSeconds(15))
    .refreshThreshold(Duration.ofMinutes(5))
    .scope("read write")
    .build();

// Custom HTTP client configuration
OkHttpClient customClient = new OkHttpClient.Builder()
    .connectTimeout(Duration.ofSeconds(10))
    .readTimeout(Duration.ofSeconds(10))
    .addInterceptor(new LoggingInterceptor())
    .build();

TokenConfig configWithCustomClient = config.toBuilder()
    .httpClient(customClient)
    .build();

try (Oauth2TokenManager tokenManager = new Oauth2TokenManager(configWithCustomClient)) {
    String token = tokenManager.getToken();
    // Use token...
}
```

### Error Handling

```java
try {
    String token = tokenManager.getToken();
} catch (InvalidCredentialsException e) {
    // Handle invalid credentials
    log.error("Authentication failed: {}", e.getMessage());
} catch (InvalidEndpointException e) {
    // Handle invalid endpoint configuration
    log.error("Invalid endpoint configuration: {}", e.getMessage());
} catch (ServiceUnavailableException e) {
    // Handle service unavailability
    log.error("OAuth2 service unavailable: {}", e.getMessage());
}
```

Remember to properly close the token manager when it's no longer needed:

```java
tokenManager.close();
```

The Token Manager implements `AutoCloseable`, so it can be used in a try-with-resources block for automatic resource cleanup.
