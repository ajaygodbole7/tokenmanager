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

@DisplayName("Conditional Mapping Tests")
@Execution(ExecutionMode.CONCURRENT)
class ConditionalMappingTest {
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

  // GREATER THAN OPERATOR TESTS - POSITIVE CASES
  @Test
  @DisplayName("Should match when greater than condition is true for integer")
  void shouldMatchWhenGreaterThanIsTrueForInteger() throws Exception {
    String sourceJson = """
            {
                "amount": 1500
            }
            """;

    String mappingJson = """
            {
                "tier": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.amount",
                            "operator": "gt",
                            "value": 1000,
                            "result": "HIGH_TIER"
                        }
                    ],
                    "default": "LOW_TIER"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("tier").asText()).isEqualTo("HIGH_TIER");
  }

  @Test
  @DisplayName("Should match when greater than condition is true for decimal")
  void shouldMatchWhenGreaterThanIsTrueForDecimal() throws Exception {
    String sourceJson = """
            {
                "amount": 100.01
            }
            """;

    String mappingJson = """
            {
                "tier": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.amount",
                            "operator": "gt",
                            "value": 100.00,
                            "result": "HIGH_TIER"
                        }
                    ],
                    "default": "LOW_TIER"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("tier").asText()).isEqualTo("HIGH_TIER");
  }

  // GREATER THAN OPERATOR TESTS - NEGATIVE CASES
  @Test
  @DisplayName("Should not match when greater than condition is false")
  void shouldNotMatchWhenGreaterThanIsFalse() throws Exception {
    String sourceJson = """
            {
                "amount": 900
            }
            """;

    String mappingJson = """
            {
                "tier": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.amount",
                            "operator": "gt",
                            "value": 1000,
                            "result": "HIGH_TIER"
                        }
                    ],
                    "default": "LOW_TIER"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("tier").asText()).isEqualTo("LOW_TIER");
  }

  // GREATER THAN OPERATOR TESTS - EDGE CASES
  @Test
  @DisplayName("Should handle equal values in greater than comparison")
  void shouldHandleEqualValuesInGreaterThan() throws Exception {
    String sourceJson = """
            {
                "amount": 1000
            }
            """;

    String mappingJson = """
            {
                "tier": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.amount",
                            "operator": "gt",
                            "value": 1000,
                            "result": "HIGH_TIER"
                        }
                    ],
                    "default": "LOW_TIER"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("tier").asText()).isEqualTo("LOW_TIER");
  }

  @Test
  @DisplayName("Should handle maximum numeric values in greater than comparison")
  void shouldHandleMaxNumericValuesInGreaterThan() throws Exception {
    String sourceJson = String.format("""
            {
                "amount": %d
            }
            """, Integer.MAX_VALUE);

    String mappingJson = """
            {
                "tier": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.amount",
                            "operator": "gt",
                            "value": 1000,
                            "result": "HIGH_TIER"
                        }
                    ],
                    "default": "LOW_TIER"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("tier").asText()).isEqualTo("HIGH_TIER");
  }

  @Test
  @DisplayName("Should handle very small decimal differences in greater than comparison")
  void shouldHandleSmallDecimalDifferencesInGreaterThan() throws Exception {
    String sourceJson = """
            {
                "amount": 1000.0000001
            }
            """;

    String mappingJson = """
            {
                "tier": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.amount",
                            "operator": "gt",
                            "value": 1000.0000000,
                            "result": "HIGH_TIER"
                        }
                    ],
                    "default": "LOW_TIER"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("tier").asText()).isEqualTo("HIGH_TIER");
  }

  // GREATER THAN OPERATOR TESTS - EXCEPTION HANDLING
  @Test
  @DisplayName("Should throw exception when comparing incompatible types")
  void shouldThrowExceptionForIncompatibleTypesInGreaterThan() {
    String sourceJson = """
            {
                "amount": "not a number"
            }
            """;

    String mappingJson = """
            {
                "tier": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.amount",
                            "operator": "gt",
                            "value": 1000,
                            "result": "HIGH_TIER"
                        }
                    ],
                    "default": "LOW_TIER"
                }
            }
            """;

    assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class);
  }

  @Test
  @DisplayName("Should throw exception when path is invalid in greater than comparison")
  void shouldThrowExceptionForInvalidPathInGreaterThan() {
    String sourceJson = """
            {
                "amount": 1500
            }
            """;

    String mappingJson = """
            {
                "tier": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.[invalid",
                            "operator": "gt",
                            "value": 1000,
                            "result": "HIGH_TIER"
                        }
                    ],
                    "default": "LOW_TIER"
                }
            }
            """;

    assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class);
  }

  @Test
  @DisplayName("Should handle null values gracefully in greater than comparison")
  void shouldHandleNullValuesInGreaterThan() throws Exception {
    String sourceJson = """
            {
                "amount": null
            }
            """;

    String mappingJson = """
            {
                "tier": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.amount",
                            "operator": "gt",
                            "value": 1000,
                            "result": "HIGH_TIER"
                        }
                    ],
                    "default": "LOW_TIER"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("tier").asText()).isEqualTo("LOW_TIER");
  }

  // LESS THAN OR EQUAL TO OPERATOR TESTS - POSITIVE CASES
  @Test
  @DisplayName("[LTE] Should match when value is less than threshold - Integer")
  void shouldMatchWhenIntegerValueIsLessThanThreshold() throws Exception {
    String sourceJson = """
            {
                "stock": 8
            }
            """;

    String mappingJson = """
            {
                "status": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.stock",
                            "operator": "lte",
                            "value": 10,
                            "result": "LOW_STOCK"
                        }
                    ],
                    "default": "NORMAL_STOCK"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("status").asText()).isEqualTo("LOW_STOCK");
  }

  @Test
  @DisplayName("[LTE] Should match when value is less than threshold - Decimal")
  void shouldMatchWhenDecimalValueIsLessThanThreshold() throws Exception {
    String sourceJson = """
            {
                "temperature": 36.5
            }
            """;

    String mappingJson = """
            {
                "status": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.temperature",
                            "operator": "lte",
                            "value": 37.0,
                            "result": "NORMAL"
                        }
                    ],
                    "default": "FEVER"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("status").asText()).isEqualTo("NORMAL");
  }

  @Test
  @DisplayName("[LTE] Should match when value equals threshold - Integer")
  void shouldMatchWhenIntegerValueEqualsThreshold() throws Exception {
    String sourceJson = """
            {
                "stock": 10
            }
            """;

    String mappingJson = """
            {
                "status": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.stock",
                            "operator": "lte",
                            "value": 10,
                            "result": "LOW_STOCK"
                        }
                    ],
                    "default": "NORMAL_STOCK"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("status").asText()).isEqualTo("LOW_STOCK");
  }

  @Test
  @DisplayName("[LTE] Should match when value equals threshold - Decimal")
  void shouldMatchWhenDecimalValueEqualsThreshold() throws Exception {
    String sourceJson = """
            {
                "temperature": 37.0
            }
            """;

    String mappingJson = """
            {
                "status": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.temperature",
                            "operator": "lte",
                            "value": 37.0,
                            "result": "NORMAL"
                        }
                    ],
                    "default": "FEVER"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("status").asText()).isEqualTo("NORMAL");
  }

  // LESS THAN OR EQUAL TO OPERATOR TESTS - NEGATIVE CASES
  @Test
  @DisplayName("[LTE] Should not match when value is greater than threshold - Integer")
  void shouldNotMatchWhenIntegerValueIsGreaterThanThreshold() throws Exception {
    String sourceJson = """
            {
                "stock": 15
            }
            """;

    String mappingJson = """
            {
                "status": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.stock",
                            "operator": "lte",
                            "value": 10,
                            "result": "LOW_STOCK"
                        }
                    ],
                    "default": "NORMAL_STOCK"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("status").asText()).isEqualTo("NORMAL_STOCK");
  }

  @Test
  @DisplayName("[LTE] Should not match when value is greater than threshold - Decimal")
  void shouldNotMatchWhenDecimalValueIsGreaterThanThreshold() throws Exception {
    String sourceJson = """
            {
                "temperature": 37.5
            }
            """;

    String mappingJson = """
            {
                "status": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.temperature",
                            "operator": "lte",
                            "value": 37.0,
                            "result": "NORMAL"
                        }
                    ],
                    "default": "FEVER"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("status").asText()).isEqualTo("FEVER");
  }

  // LESS THAN OR EQUAL TO OPERATOR TESTS - EDGE CASES
  @Test
  @DisplayName("[LTE] Should handle minimum integer value")
  void shouldHandleMinimumIntegerValue() throws Exception {
    String sourceJson = String.format("""
            {
                "value": %d
            }
            """, Integer.MIN_VALUE);

    String mappingJson = String.format("""
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "lte",
                            "value": %d,
                            "result": "AT_OR_BELOW_MIN"
                        }
                    ],
                    "default": "ABOVE_MIN"
                }
            }
            """, Integer.MIN_VALUE);

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("AT_OR_BELOW_MIN");
  }

  @Test
  @DisplayName("[LTE] Should handle very small decimal differences")
  void shouldHandleSmallDecimalDifferences() throws Exception {
    String sourceJson = """
            {
                "value": 10.0000001
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "lte",
                            "value": 10.0000000,
                            "result": "WITHIN_LIMIT"
                        }
                    ],
                    "default": "EXCEEDS_LIMIT"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("EXCEEDS_LIMIT");
  }

  @Test
  @DisplayName("[LTE] Should handle large decimal numbers")
  void shouldHandleLargeDecimalNumbers() throws Exception {
    String sourceJson = """
            {
                "value": 999999.999999
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "lte",
                            "value": 1000000.000000,
                            "result": "WITHIN_LIMIT"
                        }
                    ],
                    "default": "EXCEEDS_LIMIT"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("WITHIN_LIMIT");
  }

  // LESS THAN OR EQUAL TO OPERATOR TESTS - EXCEPTION HANDLING
  @Test
  @DisplayName("[LTE] Should throw exception for non-numeric comparison")
  void shouldThrowExceptionForNonNumericComparison() {
    String sourceJson = """
            {
                "value": "not a number"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "lte",
                            "value": 10.0,
                            "result": "WITHIN_LIMIT"
                        }
                    ],
                    "default": "EXCEEDS_LIMIT"
                }
            }
            """;

    assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class);
  }

  @Test
  @DisplayName("[LTE] Should throw exception for invalid path expression")
  void shouldThrowExceptionForInvalidPath() {
    String sourceJson = """
            {
                "value": 10
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.[invalid",
                            "operator": "lte",
                            "value": 10.0,
                            "result": "WITHIN_LIMIT"
                        }
                    ],
                    "default": "EXCEEDS_LIMIT"
                }
            }
            """;

    assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class);
  }

  @Test
  @DisplayName("[LTE] Should handle null value by returning default")
  void shouldHandleNullValue() throws Exception {
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
                            "operator": "lte",
                            "value": 10.0,
                            "result": "WITHIN_LIMIT"
                        }
                    ],
                    "default": "INVALID_INPUT"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("INVALID_INPUT");
  }

  @Test
  @DisplayName("[LTE] Should handle missing field by returning default")
  void shouldHandleMissingField() throws Exception {
    String sourceJson = """
            {
                "otherField": 10.0
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.value",
                            "operator": "lte",
                            "value": 10.0,
                            "result": "WITHIN_LIMIT"
                        }
                    ],
                    "default": "MISSING_FIELD"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("MISSING_FIELD");
  }

  // EQUALS OPERATOR TESTS - POSITIVE CASES
  @Test
  @DisplayName("[EQ] Should match when string values are equal")
  void equalsOperator_ShouldMatchStringValues() throws Exception {
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
                            "result": "STATUS_ACTIVE"
                        }
                    ],
                    "default": "STATUS_OTHER"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("STATUS_ACTIVE");
  }

  @Test
  @DisplayName("[EQ] Should match when integer values are equal")
  void equalsOperator_ShouldMatchIntegerValues() throws Exception {
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
                            "result": "SUCCESS"
                        }
                    ],
                    "default": "FAILURE"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("[EQ] Should match when decimal values are equal")
  void equalsOperator_ShouldMatchDecimalValues() throws Exception {
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
                            "result": "STANDARD_PRICE"
                        }
                    ],
                    "default": "CUSTOM_PRICE"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("STANDARD_PRICE");
  }

  @Test
  @DisplayName("[EQ] Should match when boolean values are equal")
  void equalsOperator_ShouldMatchBooleanValues() throws Exception {
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
                            "result": "FEATURE_ON"
                        }
                    ],
                    "default": "FEATURE_OFF"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("FEATURE_ON");
  }

  // EQUALS OPERATOR TESTS - NEGATIVE CASES
  @Test
  @DisplayName("[EQ] Should not match when string values differ")
  void equalsOperator_ShouldNotMatchDifferentStrings() throws Exception {
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
                            "result": "STATUS_ACTIVE"
                        }
                    ],
                    "default": "STATUS_OTHER"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("STATUS_OTHER");
  }

  @Test
  @DisplayName("[EQ] Should not match when numeric values differ")
  void equalsOperator_ShouldNotMatchDifferentNumbers() throws Exception {
    String sourceJson = """
            {
                "amount": 100.00
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
    assertThat(result.get("result").asText()).isEqualTo("NO_MATCH");
  }

  // EQUALS OPERATOR TESTS - EDGE CASES
  @Test
  @DisplayName("[EQ] Should be case-sensitive for string comparison")
  void equalsOperator_ShouldBeCaseSensitive() throws Exception {
    String sourceJson = """
            {
                "status": "Active"
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

  @Test
  @DisplayName("[EQ] Should handle exact decimal precision")
  void equalsOperator_ShouldHandleExactDecimalPrecision() throws Exception {
    String sourceJson = """
            {
                "amount": 10.100
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
                            "value": 10.1,
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
  @DisplayName("[EQ] Should handle empty string comparison")
  void equalsOperator_ShouldHandleEmptyStrings() throws Exception {
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
                            "result": "EMPTY"
                        }
                    ],
                    "default": "NOT_EMPTY"
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("EMPTY");
  }

  @Test
  @DisplayName("[EQ] Should handle whitespace in string comparison")
  void equalsOperator_ShouldHandleWhitespace() throws Exception {
    String sourceJson = """
            {
                "value": "test "
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

  // EQUALS OPERATOR TESTS - EXCEPTION HANDLING
  @Test
  @DisplayName("[EQ] Should handle null values")
  void equalsOperator_ShouldHandleNullValues() throws Exception {
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
  @DisplayName("[EQ] Should handle missing fields")
  void equalsOperator_ShouldHandleMissingFields() throws Exception {
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
  @DisplayName("[EQ] Should throw exception for invalid path")
  void equalsOperator_ShouldThrowExceptionForInvalidPath() {
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
  @DisplayName("[EQ] Should handle type mismatches")
  void equalsOperator_ShouldHandleTypeMismatches() throws Exception {
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

}
