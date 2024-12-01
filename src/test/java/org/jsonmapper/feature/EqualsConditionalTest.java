package org.jsonmapper.feature;

import static org.assertj.core.api.Assertions.*;

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
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@DisplayName("Equals Operator Conditional Tests")
@Execution(ExecutionMode.CONCURRENT)
class EqualsConditionalTest {
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

  // STRING COMPARISON TESTS
  @Test
  @DisplayName("[EQ] Should match simple string values")
  void shouldMatchSimpleStrings() throws Exception {
    String sourceJson = """
            {
                "status": "active"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.status",
                            "operator": "eq",
                            "value": "active",
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
  @DisplayName("[EQ] Should not match different string values")
  void shouldNotMatchDifferentStrings() throws Exception {
    String sourceJson = """
            {
                "status": "inactive"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.status",
                            "operator": "eq",
                            "value": "active",
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

  // NUMERIC COMPARISON TESTS
  @Test
  @DisplayName("[EQ] Should match integer values")
  void shouldMatchIntegers() throws Exception {
    String sourceJson = """
            {
                "code": 200
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.code",
                            "operator": "eq",
                            "value": 200,
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
  @DisplayName("[EQ] Should match decimal values")
  void shouldMatchDecimals() throws Exception {
    String sourceJson = """
            {
                "amount": 99.99
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.amount",
                            "operator": "eq",
                            "value": 99.99,
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

  // BOOLEAN COMPARISON TESTS
  @Test
  @DisplayName("[EQ] Should match boolean values")
  void shouldMatchBooleans() throws Exception {
    String sourceJson = """
            {
                "enabled": true
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.enabled",
                            "operator": "eq",
                            "value": true,
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

  // STRING EDGE CASES
  @Test
  @DisplayName("[EQ] Should match string with special characters")
  void shouldMatchStringWithSpecialChars() throws Exception {
    String sourceJson = """
            {
                "value": "test@123!"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "eq",
                            "value": "test@123!",
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
  @DisplayName("[EQ] Should match empty string")
  void shouldMatchEmptyString() throws Exception {
    String sourceJson = """
            {
                "value": ""
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "eq",
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

  // TYPE MISMATCH CASES
  @Test
  @DisplayName("[EQ] Should not match when comparing string to number")
  void shouldNotMatchStringToNumber() throws Exception {
    String sourceJson = """
            {
                "value": "123"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "eq",
                            "value": 123,
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
  @DisplayName("[EQ] Should not match when comparing different numeric types")
  void shouldNotMatchDifferentNumericTypes() throws Exception {
    String sourceJson = """
            {
                "value": 123
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "eq",
                            "value": 123.0,
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

  // NULL HANDLING
  @Test
  @DisplayName("[EQ] Should match null values")
  void shouldMatchNullValues() throws Exception {
    String sourceJson = """
            {
                "value": null
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "eq",
                            "value": null,
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

  // EXCEPTION HANDLING
  @Test
  @DisplayName("[EQ] Should throw exception for invalid path")
  void shouldThrowExceptionForInvalidPath() {
    String sourceJson = """
            {
                "value": "test"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.[invalid",
                            "operator": "eq",
                            "value": "test",
                            "result": "MATCH"
                        }
                    ],
                    "default": "NO_MATCH"
                }
            }
            """;

    assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class);
  }

  @Test
  @DisplayName("[EQ] Should handle missing field by returning default")
  void shouldHandleMissingField() throws Exception {
    String sourceJson = """
            {
                "otherField": "test"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "eq",
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
  @DisplayName("[EQ] Should match when decimal values are equal")
  void equalsOperator_ShouldMatchEqualDecimals() throws Exception {
    String sourceJson = """
            {
                "value": 123.45
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "eq",
                            "value": 123.45,
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
  @DisplayName("[EQ] Should not match when decimal values differ")
  void equalsOperator_ShouldNotMatchDifferentDecimals() throws Exception {
    String sourceJson = """
            {
                "value": 123.45
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "eq",
                            "value": 123.46,
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
  @DisplayName("[EQ] Should not match when comparing decimal to integer")
  void equalsOperator_ShouldNotMatchDecimalWithInteger() throws Exception {
    String sourceJson = """
            {
                "value": 123.00
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "eq",
                            "value": 123,
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
  @DisplayName("[EQ] Should handle high precision decimal comparison")
  void equalsOperator_ShouldHandleHighPrecisionDecimals() throws Exception {
    String sourceJson = """
            {
                "value": 123.4567890123
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "eq",
                            "value": 123.4567890123,
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
  @DisplayName("[EQ] Should handle negative decimal values")
  void equalsOperator_ShouldHandleNegativeDecimals() throws Exception {
    String sourceJson = """
            {
                "value": -123.45
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "eq",
                            "value": -123.45,
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
  @DisplayName("[EQ] Should match zero with different decimal precision")
  void equalsOperator_ShouldMatchZeroWithDifferentPrecision() throws Exception {
    String sourceJson = """
            {
                "value": 0.00
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "eq",
                            "value": 0.0,
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

}
