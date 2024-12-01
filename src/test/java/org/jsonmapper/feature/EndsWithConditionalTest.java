package org.jsonmapper.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ConcurrentHashMap;
import org.jsonmapper.JsonMapper;
import org.jsonmapper.JsonTransformationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

public class EndsWithConditionalTest {
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

  //ENDS WITH OPERATOR TESTS

  // Positive Test Cases
  @Test
  @DisplayName("[ENDS_WITH] Should match when string ends with suffix")
  void endsWithOperator_ShouldMatchSuffix() throws Exception {
    String sourceJson = """
            {
                "text": "Hello World"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "endsWith",
                            "value": "World",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("MATCH");
  }

  @Test
  @DisplayName("[ENDS_WITH] Should match empty string suffix")
  void endsWithOperator_ShouldMatchEmptyStringSuffix() throws Exception {
    String sourceJson = """
            {
                "text": "Hello World"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "endsWith",
                            "value": "",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("MATCH");
  }

  @Test
  @DisplayName("[ENDS_WITH] Should match when string is exactly the suffix")
  void endsWithOperator_ShouldMatchExactString() throws Exception {
    String sourceJson = """
            {
                "text": "World"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "endsWith",
                            "value": "World",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("MATCH");
  }

  // Negative Test Cases
  @Test
  @DisplayName("[ENDS_WITH] Should not match when string doesn't end with suffix")
  void endsWithOperator_ShouldNotMatchWrongSuffix() throws Exception {
    String sourceJson = """
            {
                "text": "Hello World"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "endsWith",
                            "value": "Hello",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("NO_MATCH");
  }

  @Test
  @DisplayName("[ENDS_WITH] Should not match when suffix is longer than string")
  void endsWithOperator_ShouldNotMatchLongerSuffix() throws Exception {
    String sourceJson = """
            {
                "text": "World"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "endsWith",
                            "value": "Hello World",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("NO_MATCH");
  }

  @Test
  @DisplayName("[ENDS_WITH] Should handle case sensitivity")
  void endsWithOperator_ShouldHandleCaseSensitivity() throws Exception {
    String sourceJson = """
            {
                "text": "Hello World"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "endsWith",
                            "value": "world",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("NO_MATCH");
  }

  // Null and Empty Value Handling
  @Test
  @DisplayName("[ENDS_WITH] Should handle null value in source")
  void endsWithOperator_ShouldHandleNullValue() throws Exception {
    String sourceJson = """
            {
                "text": null
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "endsWith",
                            "value": "test",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("NO_MATCH");
  }

  @Test
  @DisplayName("[ENDS_WITH] Should handle empty source string")
  void endsWithOperator_ShouldHandleEmptySourceString() throws Exception {
    String sourceJson = """
            {
                "text": ""
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "endsWith",
                            "value": "test",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("NO_MATCH");
  }

  // Special Character Handling
  @Test
  @DisplayName("[ENDS_WITH] Should handle special characters")
  void endsWithOperator_ShouldHandleSpecialCharacters() throws Exception {
    String sourceJson = """
            {
                "text": "Hello !@#$%^&*()_+"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "endsWith",
                            "value": "!@#$%^&*()_+",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("MATCH");
  }

  @Test
  @DisplayName("[ENDS_WITH] Should handle unicode characters")
  void endsWithOperator_ShouldHandleUnicodeCharacters() throws Exception {
    String sourceJson = """
            {
                "text": "Hello 世界"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "endsWith",
                            "value": "世界",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("MATCH");
  }

  // Exception Handling
  @Test
  @DisplayName("[ENDS_WITH] Should throw exception for missing operator")
  void endsWithOperator_ShouldThrowExceptionForMissingOperator() {
    String sourceJson = """
            {
                "text": "Hello World"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "value": "World",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class);
        //.hasMessageContaining("Missing required field in rule: operator");
  }

  @Test
  @DisplayName("[ENDS_WITH] Should throw exception for missing path")
  void endsWithOperator_ShouldThrowExceptionForMissingPath() {
    String sourceJson = """
            {
                "text": "Hello World"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "operator": "endsWith",
                            "value": "World",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class)
        //.hasMessageContaining("Missing required field in rule: path")
    ;
  }

  @Test
  @DisplayName("[ENDS_WITH] Should throw exception for invalid JsonPath")
  void endsWithOperator_ShouldThrowExceptionForInvalidPath() {
    String sourceJson = """
            {
                "text": "Hello World"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.[invalid",
                            "operator": "endsWith",
                            "value": "World",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class);
        //.hasMessageContaining("Invalid JsonPath");
  }

  @Test
  @DisplayName("[ENDS_WITH] Should throw exception for missing value")
  void endsWithOperator_ShouldThrowExceptionForMissingValue() {
    String sourceJson = """
            {
                "text": "Hello World"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "endsWith",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class);
        //.hasMessageContaining("Missing required field in rule: value");
  }

}
