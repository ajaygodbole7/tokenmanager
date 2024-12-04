package org.jsonmapper.feature;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jsonmapper.JsonMapper;
import org.jsonmapper.JsonTransformationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@DisplayName("Built-in Functions Tests")
@Execution(ExecutionMode.CONCURRENT)
class BuiltInFunctionsTest {
  private static final ConcurrentHashMap<Long, JsonMapper> JSON_MAPPER_STORE = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<Long, ObjectMapper> OBJECT_MAPPER_STORE = new ConcurrentHashMap<>();

  private JsonMapper jsonMapper;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    long threadId = Thread.currentThread().getId();
    jsonMapper = JSON_MAPPER_STORE.computeIfAbsent(threadId, k -> new JsonMapper());
    objectMapper = OBJECT_MAPPER_STORE.computeIfAbsent(threadId, k -> new ObjectMapper());
  }

  @AfterEach
  void tearDown() {
    long threadId = Thread.currentThread().getId();
    JSON_MAPPER_STORE.remove(threadId);
    OBJECT_MAPPER_STORE.remove(threadId);
  }

  @AfterAll
  static void cleanupAll() {
    JSON_MAPPER_STORE.clear();
    OBJECT_MAPPER_STORE.clear();
  }

  private JsonNode getCleanJson(String json) throws Exception {
    return new ObjectMapper().readTree(json);
  }

  @Nested
  @DisplayName("String Functions Tests")
  class StringFunctionsTest {
    @Test
    @DisplayName("$string should convert input to string")
    void stringFunction_ShouldConvertToString() throws Exception {
      String sourceJson = """
                    {
                        "number": 42
                    }
                    """;

      String mappingJson = """
                    {
                        "result": {
                            "type": "function",
                            "function": "$string",
                            "sourcePath": "$.number"
                        }
                    }
                    """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      assertThat(result.get("result").asText()).isEqualTo("42");
    }

    @Test
    @DisplayName("$uppercase should convert string to uppercase")
    void upperCaseFunction_ShouldConvertToUpperCase() throws Exception {
      String sourceJson = """
                    {
                        "text": "Hello World"
                    }
                    """;

      String mappingJson = """
                    {
                        "result": {
                            "type": "function",
                            "function": "$uppercase",
                            "sourcePath": "$.text"
                        }
                    }
                    """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      assertThat(result.get("result").asText()).isEqualTo("HELLO WORLD");
    }

    @Test
    @DisplayName("$lowercase should convert string to lowercase")
    void lowerCaseFunction_ShouldConvertToLowerCase() throws Exception {
      String sourceJson = """
                    {
                        "text": "Hello World"
                    }
                    """;

      String mappingJson = """
                    {
                        "result": {
                            "type": "function",
                            "function": "$lowercase",
                            "sourcePath": "$.text"
                        }
                    }
                    """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      assertThat(result.get("result").asText()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("$trim should remove leading and trailing whitespace")
    void trimFunction_ShouldRemoveWhitespace() throws Exception {
      String sourceJson = """
                    {
                        "text": "  Hello World  "
                    }
                    """;

      String mappingJson = """
                    {
                        "result": {
                            "type": "function",
                            "function": "$trim",
                            "sourcePath": "$.text"
                        }
                    }
                    """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      assertThat(result.get("result").asText()).isEqualTo("Hello World");
    }

    @Test
    @DisplayName("$substring should extract part of string")
    void substringFunction_ShouldExtractSubstring() throws Exception {
      String sourceJson = """
                    {
                        "text": "Hello World"
                    }
                    """;

      String mappingJson = """
                    {
                        "result": {
                            "type": "function",
                            "function": "$substring",
                            "sourcePath": "$.text",
                            "args": [0, 5]
                        }
                    }
                    """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      assertThat(result.get("result").asText()).isEqualTo("Hello");
    }
  }

  @Nested
  @DisplayName("Numeric Functions Tests")
  class NumericFunctionsTest {
    @Test
    @DisplayName("$number should convert string to number")
    void numberFunction_ShouldConvertToNumber() throws Exception {
      String sourceJson = """
                    {
                        "value": "42.5"
                    }
                    """;

      String mappingJson = """
                    {
                        "result": {
                            "type": "function",
                            "function": "$number",
                            "sourcePath": "$.value"
                        }
                    }
                    """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      assertThat(result.get("result").decimalValue())
          .isEqualByComparingTo(new BigDecimal("42.5"));
    }

    @Test
    @DisplayName("$round should round number to specified scale")
    void roundFunction_ShouldRoundNumber() throws Exception {
      String sourceJson = """
                    {
                        "value": 42.567
                    }
                    """;

      String mappingJson = """
                    {
                        "result": {
                            "type": "function",
                            "function": "$round",
                            "sourcePath": "$.value",
                            "args": [2]
                        }
                    }
                    """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      assertThat(result.get("result").decimalValue())
          .isEqualByComparingTo(new BigDecimal("42.57"));
    }

    @Test
    @DisplayName("$sum should add numbers in array")
    void sumFunction_ShouldSumArray() throws Exception {
      String sourceJson = """
                    {
                        "numbers": [1, 2, 3, 4, 5]
                    }
                    """;

      String mappingJson = """
                    {
                        "result": {
                            "type": "function",
                            "function": "$sum",
                            "sourcePath": "$.numbers"
                        }
                    }
                    """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      assertThat(result.get("result").decimalValue())
          .isEqualByComparingTo(new BigDecimal("15"));
    }
  }

  @Nested
  @DisplayName("Date Functions Tests")
  class DateFunctionsTest {
    @Test
    @DisplayName("$now should return current date time")
    void nowFunction_ShouldReturnCurrentDateTime() throws Exception {
      String sourceJson = """
                    {
                        "dummy": "value"
                    }
                    """;

      String mappingJson = """
                    {
                        "result": {
                            "type": "function",
                            "function": "$now"
                        }
                    }
                    """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      String dateTimeStr = result.get("result").asText();
      assertThat(dateTimeStr).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
    }

    @Test
    @DisplayName("$formatDate should correctly format ISO8601 date-time string")
    void formatDateFunction_ShouldFormatISO8601DateTime() throws Exception {
      String sourceJson = """
                  {
                      "date": "2023-12-31T23:59:59Z"
                  }
                  """;

      String mappingJson = """
                  {
                      "result": {
                          "type": "function",
                          "function": "$formatDate",
                          "sourcePath": "$.date"                    
                      }
                  }
                  """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      assertThat(result.get("result").asText()).isEqualTo("2023-12-31T23:59:59Z");
    }

    @Test
    @DisplayName("$formatDate should add default time for date without time")
    void formatDateFunction_ShouldAddDefaultTime() throws Exception {
      String sourceJson = """
                  {
                      "date": "2023-12-31"
                  }
                  """;

      String mappingJson = """
                  {
                      "result": {
                          "type": "function",
                          "function": "$formatDate",
                          "sourcePath": "$.date"                    
                      }
                  }
                  """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      assertThat(result.get("result").asText()).isEqualTo("2023-12-31T00:00:00Z");
    }

    @Test
    @DisplayName("$formatDate should throw JsonTransformationException for invalid date input")
    void formatDateFunction_ShouldThrowExceptionForInvalidDate() {
      String sourceJson = """
                  {
                      "date": "invalid-date"
                  }
                  """;

      String mappingJson = """
                  {
                      "result": {
                          "type": "function",
                          "function": "$formatDate",
                          "sourcePath": "$.date"                    
                      }
                  }
                  """;

      assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
          .isInstanceOf(JsonTransformationException.class)
         ;
    }

    @Test
    @DisplayName("$formatDate should throw JsonTransformationException for empty string input")
    void formatDateFunction_ShouldThrowExceptionForEmptyInput() {
      String sourceJson = """
                  {
                      "date": ""
                  }
                  """;

      String mappingJson = """
                  {
                      "result": {
                          "type": "function",
                          "function": "$formatDate",
                          "sourcePath": "$.date"                    
                      }
                  }
                  """;

      assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
          .isInstanceOf(JsonTransformationException.class);
    }

  }

  @Nested
  @DisplayName("Utility Functions Tests")
  class UtilityFunctionsTest {
    @Test
    @DisplayName("$uuid should generate valid UUID")
    void uuidFunction_ShouldGenerateUUID() throws Exception {
      String sourceJson = """
                    {
                        "dummy": "value"
                    }
                    """;

      String mappingJson = """
                    {
                        "result": {
                            "type": "function",
                            "function": "$uuid"
                        }
                    }
                    """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      String uuid = result.get("result").asText();
      assertThat(UUID.fromString(uuid)).isNotNull();
    }

    @Test
    @DisplayName("$concat should concatenate strings")
    void concatFunction_ShouldConcatenateStrings() throws Exception {
      String sourceJson = """
                    {
                        "text": "Hello"
                    }
                    """;

      String mappingJson = """
                    {
                        "result": {
                            "type": "function",
                            "function": "$concat",
                            "sourcePath": "$.text",
                            "args": [" ", "World"]
                        }
                    }
                    """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      assertThat(result.get("result").asText()).isEqualTo("Hello World");
    }
  }

  @Test
  @DisplayName("$concat should concatenate strings with JsonPath arguments")
  void concatFunction_ShouldHandleJsonPaths() throws Exception {
    String sourceJson = """
                {
                    "greeting": "Hello",
                    "name": "World"
                }
                """;

    String mappingJson = """
                {
                    "result": {
                        "type": "function",
                        "function": "$concat",
                        "args": ["$.greeting", " ", "$.name"]
                    }
                }
                """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("Hello World");
  }

  // Edge Cases and Error Handling
  @Nested
  @DisplayName("Edge Cases and Error Handling Tests")
  class EdgeCasesTest {
    @Test
    @DisplayName("Functions should handle null input")
    void functions_ShouldHandleNullInput() throws Exception {
      String sourceJson =
          """
            {
                "value": null
            }
            """;

      String mappingJson =
          """
            {
                "string": {
                    "type": "function",
                    "function": "$string",
                    "sourcePath": "$.value"
                },
                "number": {
                    "type": "function",
                    "function": "$number",
                    "sourcePath": "$.value"
                }
            }
            """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      assertThat(result.get("string").asText()).isEqualTo(""); // Expect empty string for null
      assertThat(result.get("number").isNull()).isTrue(); // Expect null for invalid number
    }

    @Test
    @DisplayName("$substring should handle out of bounds indices")
    void substringFunction_ShouldHandleOutOfBounds() throws Exception {
      String sourceJson =
          """
                    {
                        "text": "Hello"
                    }
                    """;

      String mappingJson =
          """
                    {
                        "result": {
                            "type": "function",
                            "function": "$substring",
                            "sourcePath": "$.text",
                            "args": [0, 10]
                        }
                    }
                    """;

      JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
      assertThat(result.get("result").asText()).isEqualTo("Hello");
    }
  }

}
