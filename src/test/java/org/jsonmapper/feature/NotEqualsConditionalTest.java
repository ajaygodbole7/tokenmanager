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

@DisplayName("Not Equals Operator Conditional Tests")
@Execution(ExecutionMode.CONCURRENT)
class NotEqualsConditionalTest {
  private static final ConcurrentHashMap<Long, JsonMapper> JSON_MAPPER_STORE = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<Long, ObjectMapper> OBJECT_MAPPER_STORE = new ConcurrentHashMap<>();

  private JsonMapper jsonMapper;
  private ObjectMapper objectMapper;

  @AfterAll
  static void cleanupAll() {
    JSON_MAPPER_STORE.clear();
    OBJECT_MAPPER_STORE.clear();
  }

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

  private JsonNode getCleanJson(String json) throws Exception {
    return new ObjectMapper().readTree(json);
  }

  // STRING COMPARISON TESTS
  @Test
  @DisplayName("[NE] Should match when string values are different")
  void notEqualsOperator_ShouldMatchDifferentStrings() throws Exception {
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
                            "operator": "ne",
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
  @DisplayName("[NE] Should not match when string values are same")
  void notEqualsOperator_ShouldNotMatchSameStrings() throws Exception {
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
                            "operator": "ne",
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

  @Test
  @DisplayName("[NE] Should match when comparing empty to non-empty string")
  void notEqualsOperator_ShouldMatchEmptyVsNonEmptyString() throws Exception {
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
                            "operator": "ne",
                            "value": "test",
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
  @DisplayName("[NE] Should not match when both strings are empty")
  void notEqualsOperator_ShouldNotMatchBothEmptyStrings() throws Exception {
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
                            "operator": "ne",
                            "value": "",
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

  // INTEGER COMPARISON TESTS
  @Test
  @DisplayName("[NE] Should match when integer values are different")
  void notEqualsOperator_ShouldMatchDifferentIntegers() throws Exception {
    String sourceJson = """
            {
                "code": 404
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.code",
                            "operator": "ne",
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
  @DisplayName("[NE] Should not match when integer values are same")
  void notEqualsOperator_ShouldNotMatchSameIntegers() throws Exception {
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
                            "operator": "ne",
                            "value": 200,
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

  // BOOLEAN COMPARISON TESTS
  @Test
  @DisplayName("[NE] Should match when boolean values are different")
  void notEqualsOperator_ShouldMatchDifferentBooleans() throws Exception {
    String sourceJson = """
            {
                "enabled": false
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.enabled",
                            "operator": "ne",
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

  @Test
  @DisplayName("[NE] Should not match when boolean values are same")
  void notEqualsOperator_ShouldNotMatchSameBooleans() throws Exception {
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
                            "operator": "ne",
                            "value": true,
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
  @DisplayName("[NE] Should not match when both values are null")
  void shouldNotMatchBothNull() throws Exception {
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
                            "operator": "ne",
                            "value": null,
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

  // DECIMAL COMPARISON TESTS
  @Test
  @DisplayName("[NE] Should match when decimal values differ")
  void shouldMatchDifferentDecimals() throws Exception {
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
                            "operator": "ne",
                            "value": 123.46,
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
  @DisplayName("[NE] Should not match when decimal values are same")
  void shouldNotMatchSameDecimals() throws Exception {
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
                            "operator": "ne",
                            "value": 123.45,
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
  @DisplayName("[NE] Should match when comparing decimal to integer")
  void shouldMatchDecimalToInteger() throws Exception {
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
                            "operator": "ne",
                            "value": 123,
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
  @DisplayName("[NE] Should match when negative decimal values differ")
  void shouldMatchDifferentNegativeDecimals() throws Exception {
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
                            "operator": "ne",
                            "value": -123.46,
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
  @DisplayName("[NE] Should match high precision decimal differences")
  void shouldMatchHighPrecisionDifferences() throws Exception {
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
                            "operator": "ne",
                            "value": 123.4567890124,
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
  @DisplayName("[NE] Should not match zero with different decimal precision")
  void shouldNotMatchZeroWithDifferentPrecision() throws Exception {
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
                            "operator": "ne",
                            "value": 0.0,
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

  // EXCEPTION HANDLING TESTS
  @Test
  @DisplayName("[NE] Should throw exception for invalid path")
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
                            "operator": "ne",
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
  @DisplayName("[NE] Should match when field is missing")
  void shouldMatchMissingField() throws Exception {
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
                            "operator": "ne",
                            "value": "test",
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

  // DECIMAL COMPARISON TESTS
  @Test
  @DisplayName("[NE] Should match when decimal values differ")
  void notEqualsOperator_ShouldMatchDifferentDecimals() throws Exception {
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
                            "operator": "ne",
                            "value": 123.46,
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
  @DisplayName("[NE] Should not match when decimal values are same")
  void notEqualsOperator_ShouldNotMatchSameDecimals() throws Exception {
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
                            "operator": "ne",
                            "value": 123.45,
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
  @DisplayName("[NE] Should match when comparing decimal to integer")
  void notEqualsOperator_ShouldMatchDecimalToInteger() throws Exception {
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
                            "operator": "ne",
                            "value": 123,
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
  @DisplayName("[NE] Should match when negative decimal values differ")
  void notEqualsOperator_ShouldMatchDifferentNegativeDecimals() throws Exception {
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
                            "operator": "ne",
                            "value": -123.46,
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
  @DisplayName("[NE] Should match for high precision decimal differences")
  void notEqualsOperator_ShouldMatchHighPrecisionDifferences() throws Exception {
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
                            "operator": "ne",
                            "value": 123.4567890124,
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
  @DisplayName("[NE] Should not match zero with different precision")
  void notEqualsOperator_ShouldNotMatchZeroWithDifferentPrecision() throws Exception {
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
                            "operator": "ne",
                            "value": 0.0,
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

  // NULL HANDLING TESTS
  @Test
  @DisplayName("[NE] Should match when comparing null to non-null")
  void notEqualsOperator_ShouldMatchNullToNonNull() throws Exception {
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
                            "operator": "ne",
                            "value": "test",
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
  @DisplayName("[NE] Should not match when both values are null")
  void notEqualsOperator_ShouldNotMatchBothNull() throws Exception {
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
                            "operator": "ne",
                            "value": null,
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
  @DisplayName("[NE] Should match when comparing non-null to null")
  void notEqualsOperator_ShouldMatchNonNullToNull() throws Exception {
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
                            "path": "$.value",
                            "operator": "ne",
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

  // EXCEPTION HANDLING TESTS
  @Test
  @DisplayName("[NE] Should throw exception for invalid path")
  void notEqualsOperator_ShouldThrowExceptionForInvalidPath() {
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
                            "operator": "ne",
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
  @DisplayName("[NE] Should match when field is missing")
  void notEqualsOperator_ShouldMatchMissingField() throws Exception {
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
                            "operator": "ne",
                            "value": "test",
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
