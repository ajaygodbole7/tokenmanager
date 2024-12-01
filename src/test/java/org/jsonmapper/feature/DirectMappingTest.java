package org.jsonmapper.feature;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.math.BigDecimal;
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

@DisplayName("Direct Mapping Tests")
@Execution(ExecutionMode.CONCURRENT)
class DirectMappingTest {
  // Thread-safe instance management using ConcurrentHashMap
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

  // Helper method to get clean JSON from source string
  private JsonNode getCleanJson(String json) throws Exception {
    // Create a new ObjectMapper instance for each parse to ensure thread safety
    return new ObjectMapper().readTree(json);
  }


  @Test
  @DisplayName("Should map primitive types correctly")
  void shouldMapPrimitiveTypes() throws Exception {
    String sourceJson = """
            {
                "stringField": "test",
                "intField": 42,
                "doubleField": 42.42,
                "booleanField": true,
                "nullField": null
            }
            """;

    String mappingJson = """
            {
                "text": "$.stringField",
                "number": "$.intField",
                "decimal": "$.doubleField",
                "flag": "$.booleanField",
                "empty": "$.nullField"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("text").textValue()).isEqualTo("test");
    assertThat(result.get("number").intValue()).isEqualTo(42);
    assertThat(result.get("decimal").doubleValue()).isEqualTo(42.42);
    assertThat(result.get("flag").booleanValue()).isTrue();

    JsonNode emptyNode = result.get("empty");
    // Check that the "empty" field is explicitly null in the result
    assertThat(emptyNode).isNotNull(); // The key exists
    assertThat(emptyNode.isNull()).isTrue(); // The value is JSON null
  }

  @Test
  @DisplayName("Should handle special characters in field values")
  void shouldHandleSpecialCharacters() throws Exception {
    String sourceJson = """
            {
                "special": "!@#$%^&*()_+",
                "unicode": "Hello 世界",
                "whitespace": "   spaces   ",
                "newlines": "line1\\nline2"
            }
            """;

    String mappingJson = """
            {
                "specialChars": "$.special",
                "unicodeText": "$.unicode",
                "spacedText": "$.whitespace",
                "multiline": "$.newlines"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("specialChars").textValue()).isEqualTo("!@#$%^&*()_+");
    assertThat(result.get("unicodeText").textValue()).isEqualTo("Hello 世界");
    assertThat(result.get("spacedText").textValue()).isEqualTo("   spaces   ");
    assertThat(result.get("multiline").textValue()).isEqualTo("line1\nline2");
  }

  @Test
  @DisplayName("Should map deeply nested fields")
  void shouldMapDeeplyNestedFields() throws Exception {
    String sourceJson = """
            {
                "level1": {
                    "level2": {
                        "level3": {
                            "value": "nested value"
                        }
                    }
                }
            }
            """;

    String mappingJson = """
            {
                "deepValue": "$.level1.level2.level3.value"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("deepValue").textValue()).isEqualTo("nested value");
  }

  @Test
  @DisplayName("Should handle missing intermediate nodes")
  void shouldHandleMissingIntermediateNodes() throws Exception {
    String sourceJson = """
        {
            "level1": {
                "level2": null
            }
        }
        """;

    String mappingJson = """
        {
            "deepValue": "$.level1.level2.level3.value"
        }
        """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    // Get the node
    JsonNode deepValueNode = result.get("deepValue");
// Assertions
    assertThat(deepValueNode).isNotNull();
    assertThat(deepValueNode.isNull()).isTrue();
  }



  @Test
  @DisplayName("Should map array elements by index")
  void shouldMapArrayElementsByIndex() throws Exception {
    String sourceJson = """
            {
                "array": ["first", "second", "third"]
            }
            """;

    String mappingJson = """
            {
                "first": "$.array[0]",
                "second": "$.array[1]",
                "last": "$.array[2]",
                "nonExistent": "$.array[3]"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("first").asText()).isEqualTo("first");
    assertThat(result.get("second").asText()).isEqualTo("second");
    assertThat(result.get("last").asText()).isEqualTo("third");

    JsonNode nonExistentNode = result.get("nonExistent");
    // Ensure "nonExistent" is explicitly represented as JSON null
    assertThat(nonExistentNode).isNotNull(); // The key exists
    assertThat(nonExistentNode.isNull()).isTrue(); // The value is JSON null
  }

  @Test
  @DisplayName("Should map array slices")
  void shouldMapArraySlices() throws Exception {
    String sourceJson = """
        {
            "numbers": [1, 2, 3, 4, 5]
        }
        """;

    String mappingJson = """
        {
            "firstTwo": "$.numbers[0:2]",
            "lastTwo": "$.numbers[-2:]",
            "middle": "$.numbers[1:4]"
        }
        """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("firstTwo")).isNotNull().isInstanceOf(ArrayNode.class);
    assertThat(result.get("lastTwo")).isNotNull().isInstanceOf(ArrayNode.class);

    ArrayNode firstTwo = (ArrayNode) result.get("firstTwo");
    ArrayNode lastTwo = (ArrayNode) result.get("lastTwo");

    assertThat(firstTwo.size()).isEqualTo(2);
    assertThat(firstTwo.get(0).asInt()).isEqualTo(1);
    assertThat(firstTwo.get(1).asInt()).isEqualTo(2);

    assertThat(lastTwo.size()).isEqualTo(2);
    assertThat(lastTwo.get(0).asInt()).isEqualTo(4);
    assertThat(lastTwo.get(1).asInt()).isEqualTo(5);
  }


  @Test
  @DisplayName("Should throw JsonTransformationException with InvalidPathException as root cause for invalid JsonPath")
  void shouldHandleInvalidJsonPath() {
    String sourceJson = """
        {
            "field": "value"
        }
        """;

    String mappingJson = """
        {
            "invalid": "$.[invalid"
        }
        """;

    // Capture the thrown exception
    Throwable thrown = catchThrowable(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)));

    // Assert that the top-level exception is JsonTransformationException
    assertThat(thrown).isInstanceOf(JsonTransformationException.class);

    // Traverse the cause chain to find the root cause
    Throwable rootCause = thrown.getCause();
    while (rootCause != null && !(rootCause instanceof com.jayway.jsonpath.InvalidPathException)) {
      rootCause = rootCause.getCause();
    }

    // Assert the root cause is InvalidPathException
    assertThat(rootCause).isInstanceOf(com.jayway.jsonpath.InvalidPathException.class);

  }




  @Test
  @DisplayName("Should handle empty objects and arrays")
  void shouldHandleEmptyCollections() throws Exception {
    String sourceJson = """
        {
            "emptyObject": {},
            "emptyArray": [],
            "arrayWithEmpty": [{}, [], null]
        }
        """;

    String mappingJson = """
        {
            "object": "$.emptyObject",
            "array": "$.emptyArray",
            "complexArray": "$.arrayWithEmpty"
        }
        """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    // Log transformation output for debugging
    System.out.println("Transformed JSON: " + result.toPrettyString());

    // Assertions
    assertThat(result.get("object")).isNotNull();
    assertThat(result.get("object").isObject()).isTrue(); // Check if it is a JSON object

    assertThat(result.get("array")).isNotNull();
    assertThat(result.get("array").isArray()).isTrue(); // Check if it is a JSON array

    JsonNode complexArray = result.get("complexArray");
    assertThat(complexArray).isNotNull(); // Ensure it's not null
    assertThat(complexArray.isArray()).isTrue(); // Check if it's a JSON array

    // Validate "complexArray" contents
    assertThat(complexArray.size()).isEqualTo(3); // Correct size
    assertThat(complexArray.get(0).isObject()).isTrue(); // First element is object
    assertThat(complexArray.get(1).isArray()).isTrue(); // Second element is array
    assertThat(complexArray.get(2).isNull()).isTrue(); // Third element is null
  }





  @Test
  @DisplayName("Should handle maximum numeric values")
  void shouldHandleMaximumValues() throws Exception {
    // Dynamically generate the source JSON
    String sourceJson = String.format("""
        {
            "maxInt": %d,
            "minInt": %d,
            "bigNumber": %d,
            "decimal": %.17e
        }
        """, Integer.MAX_VALUE, Integer.MIN_VALUE, Long.MAX_VALUE, Double.MAX_VALUE);

    String mappingJson = """
        {
            "maxInteger": "$.maxInt",
            "minInteger": "$.minInt",
            "longNumber": "$.bigNumber",
            "maxDouble": "$.decimal"
        }
        """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    // Assertions for integers and long
    assertThat(result.get("maxInteger").asLong()).isEqualTo(Integer.MAX_VALUE);
    assertThat(result.get("minInteger").asLong()).isEqualTo(Integer.MIN_VALUE);
    assertThat(result.get("longNumber").asLong()).isEqualTo(Long.MAX_VALUE);

    // Assertion for double value using BigDecimal
    BigDecimal actualDouble = new BigDecimal(result.get("maxDouble").asText());
    BigDecimal expectedDouble = BigDecimal.valueOf(Double.MAX_VALUE);
    assertThat(actualDouble.compareTo(expectedDouble)).isEqualTo(0); // Ensure equality with high precision
  }


  //Type Coercion Test
  @Test
  @DisplayName("Should handle string to number conversions")
  void shouldHandleStringToNumberConversions() throws Exception {
    String sourceJson = """
        {
            "numberAsString": "42",
            "decimalAsString": "42.42",
            "invalidNumber": "not-a-number"
        }
        """;

    String mappingJson = """
        {
            "intValue": "$.numberAsString",
            "doubleValue": "$.decimalAsString",
            "invalidValue": "$.invalidNumber"
        }
        """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    // Assertions for "intValue"
    assertThat(result.get("intValue")).isNotNull();
    assertThat(result.get("intValue").isTextual()).isTrue(); // Remains as text
    assertThat(result.get("intValue").asText()).isEqualTo("42");

    // Assertions for "doubleValue"
    assertThat(result.get("doubleValue")).isNotNull();
    assertThat(result.get("doubleValue").isTextual()).isTrue(); // Remains as text
    assertThat(result.get("doubleValue").asText()).isEqualTo("42.42");

    // Assertions for "invalidValue"
    assertThat(result.get("invalidValue")).isNotNull();
    assertThat(result.get("invalidValue").isTextual()).isTrue(); // Remains as text
    assertThat(result.get("invalidValue").asText()).isEqualTo("not-a-number");
  }


  @Test
  @DisplayName("Should handle boolean string representations")
  void shouldHandleBooleanStrings() throws Exception {
    String sourceJson = """
        {
            "boolAsString": "true",
            "numberAsBoolean": 1,
            "yesAsBoolean": "yes"
        }
        """;

    String mappingJson = """
        {
            "boolValue": "$.boolAsString",
            "numBool": "$.numberAsBoolean",
            "yesBool": "$.yesAsBoolean"
        }
        """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("boolValue").textValue()).isEqualTo("true");
    assertThat(result.get("numBool").asInt()).isEqualTo(1);
    assertThat(result.get("yesBool").textValue()).isEqualTo("yes");
  }


  @Test
  @DisplayName("Should support different JsonPath notations")
  void shouldSupportDifferentNotations() throws Exception {
    String sourceJson = """
        {
            "store": {
                "book": [
                    {"title": "Book1", "price": 10},
                    {"title": "Book2", "price": 20}
                ]
            }
        }
        """;

    String mappingJson = """
        {
            "bracket": "$['store']['book'][0]['title']",
            "dot": "$.store.book[0].title",
            "filter": "$.store.book[?(@.price > 15)].title",
            "wildcard": "$.store.book[*].title"
        }
        """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    // Assertion for bracket notation
    assertThat(result.get("bracket").asText()).isEqualTo("Book1");

    // Assertion for dot notation
    assertThat(result.get("dot").asText()).isEqualTo("Book1");

    // Assertion for filter notation
    JsonNode filterResult = result.get("filter");
    assertThat(filterResult).isNotNull().isInstanceOf(ArrayNode.class);
    assertThat(filterResult.size()).isEqualTo(1);
    assertThat(filterResult.get(0).asText()).isEqualTo("Book2");

    // Assertion for wildcard notation
    JsonNode wildcardResult = result.get("wildcard");
    assertThat(wildcardResult).isNotNull().isInstanceOf(ArrayNode.class);
    assertThat(wildcardResult.size()).isEqualTo(2);
    assertThat(wildcardResult.get(0).asText()).isEqualTo("Book1");
    assertThat(wildcardResult.get(1).asText()).isEqualTo("Book2");
  }

  @Test
  @DisplayName("Should handle empty source JSON")
  void shouldHandleEmptySourceJson() throws Exception {
    String sourceJson = "{}";
    String mappingJson = """
        {
            "key": "$.nonExistent"
        }
        """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    // Assertions
    JsonNode emptyNode = result.get("key");

    // The key should exist in the result
    assertThat(emptyNode).isNotNull();

    // The value should be explicitly JSON null
    assertThat(emptyNode.isNull()).isTrue();
  }

  @Test
  @DisplayName("Should handle multiple JsonPath expressions for same source field")
  void shouldHandleMultipleJsonPathExpressionsForSameField() throws Exception {
    String sourceJson = """
            {
                "user": {
                    "name": "John Doe",
                    "age": 30
                }
            }
            """;

    String mappingJson = """
            {
                "name1": "$.user.name",
                "name2": "$.user.name",
                "name3": "$.user.name"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("name1").asText()).isEqualTo("John Doe");
    assertThat(result.get("name2").asText()).isEqualTo("John Doe");
    assertThat(result.get("name3").asText()).isEqualTo("John Doe");
  }

  @Test
  @DisplayName("Should throw exception for invalid JSON")
  void shouldThrowExceptionForInvalidJson() {
    String sourceJson = "Invalid JSON";
    String mappingJson = """
        {
            "key": "$.nonExistent"
        }
        """;

    Throwable thrown = catchThrowable(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)));

    assertThat(thrown).isInstanceOf(JsonTransformationException.class);
  }


  @Test
  @DisplayName("Should handle null input values consistently")
  void shouldHandleNullInputValues() {
    assertThatThrownBy(() -> jsonMapper.transform(null, getCleanJson("{}")))
        .isInstanceOf(JsonTransformationException.class)
        .hasMessageContaining("Source JSON cannot be null");

    assertThatThrownBy(() -> jsonMapper.transform("{}", null))
        .isInstanceOf(JsonTransformationException.class)
        .hasMessageContaining("Mapping rules cannot be null");
  }

  @Test
  @DisplayName("Should handle special characters in JsonPath")
  void shouldHandleSpecialCharactersInJsonPath() throws Exception {
    String sourceJson = """
            {
                "@special": {
                    "#field": "value1",
                    "$price": 42,
                    "field.with.dots": true
                }
            }
            """;

    String mappingJson = """
            {
                "special": "$['@special']['#field']",
                "price": "$['@special']['$price']",
                "dotted": "$['@special']['field.with.dots']"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("special").asText()).isEqualTo("value1");
    assertThat(result.get("price").asInt()).isEqualTo(42);
    assertThat(result.get("dotted").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("Should handle recursive paths")
  void shouldHandleRecursivePaths() throws Exception {
    String sourceJson = """
            {
                "name": "root",
                "children": [
                    {
                        "name": "child1",
                        "children": [
                            {"name": "grandchild1"},
                            {"name": "grandchild2"}
                        ]
                    },
                    {
                        "name": "child2",
                        "children": []
                    }
                ]
            }
            """;

    String mappingJson = """
            {
                "allNames": "$..name",
                "allChildren": "$..children[*].name"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    JsonNode allNames = result.get("allNames");
    assertThat(allNames).isNotNull();
    assertThat(allNames.isArray()).isTrue();
    assertThat(allNames.size()).isEqualTo(5);

    // Verify all names in order
    assertThat(allNames.get(0).asText()).isEqualTo("root");
    assertThat(allNames.get(1).asText()).isEqualTo("child1");
    assertThat(allNames.get(2).asText()).isEqualTo("grandchild1");
    assertThat(allNames.get(3).asText()).isEqualTo("grandchild2");
    assertThat(allNames.get(4).asText()).isEqualTo("child2");
  }

  @Test
  @DisplayName("Should handle complex array filters with multiple conditions")
  void shouldHandleComplexArrayFilters() throws Exception {
    String sourceJson = """
            {
                "items": [
                    {"id": 1, "value": 10, "active": true,  "type": "A"},
                    {"id": 2, "value": 20, "active": false, "type": "B"},
                    {"id": 3, "value": 30, "active": true,  "type": "A"},
                    {"id": 4, "value": 40, "active": true,  "type": "B"}
                ]
            }
            """;

    String mappingJson = """
            {
                "filteredItems": "$.items[?(@.active == true && @.type == 'A')]",
                "valueRange": "$.items[?(@.value >= 20 && @.value <= 35)]",
                "complexFilter": "$.items[?(@.active == true || (@.value > 30 && @.type == 'B'))]"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    // Check filteredItems
    JsonNode filteredItems = result.get("filteredItems");
    assertThat(filteredItems).isNotNull();
    assertThat(filteredItems.isArray()).isTrue();
    assertThat(filteredItems.size()).isEqualTo(2);
    assertThat(filteredItems.get(0).get("id").asInt()).isEqualTo(1);
    assertThat(filteredItems.get(1).get("id").asInt()).isEqualTo(3);

    // Check valueRange
    JsonNode valueRange = result.get("valueRange");
    assertThat(valueRange).isNotNull();
    assertThat(valueRange.isArray()).isTrue();
    assertThat(valueRange.size()).isEqualTo(2);
    assertThat(valueRange.get(0).get("value").asInt()).isEqualTo(20);
    assertThat(valueRange.get(1).get("value").asInt()).isEqualTo(30);

    // Check complexFilter
    JsonNode complexFilter = result.get("complexFilter");
    assertThat(complexFilter).isNotNull();
    assertThat(complexFilter.isArray()).isTrue();
    assertThat(complexFilter.size()).isEqualTo(3); // Should match id 1, 3, and 4
  }

  @Test
  @DisplayName("Should handle deeply nested arrays with mixed types")
  void shouldHandleNestedArraysWithMixedTypes() throws Exception {
    String sourceJson = """
        {
            "nestedArray": [
                [1, 2, 3],
                ["a", "b", "c"],
                [{"key": "value"}, true, null]
            ]
        }
        """;

    String mappingJson = """
        {
            "firstArray": "$.nestedArray[0]",
            "secondArray": "$.nestedArray[1]",
            "thirdArray": "$.nestedArray[2]"
        }
        """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("firstArray")).isNotNull().isInstanceOf(ArrayNode.class);
    assertThat(result.get("secondArray")).isNotNull().isInstanceOf(ArrayNode.class);
    assertThat(result.get("thirdArray")).isNotNull().isInstanceOf(ArrayNode.class);

    ArrayNode firstArray = (ArrayNode) result.get("firstArray");
    ArrayNode secondArray = (ArrayNode) result.get("secondArray");
    ArrayNode thirdArray = (ArrayNode) result.get("thirdArray");

    assertThat(firstArray.size()).isEqualTo(3);
    assertThat(firstArray.get(0).asInt()).isEqualTo(1);

    assertThat(secondArray.size()).isEqualTo(3);
    assertThat(secondArray.get(1).asText()).isEqualTo("b");

    assertThat(thirdArray.size()).isEqualTo(3);
    assertThat(thirdArray.get(0).get("key").asText()).isEqualTo("value");
    assertThat(thirdArray.get(2).isNull()).isTrue();
  }

  @Test
  @DisplayName("Should handle out-of-bound slices gracefully")
  void shouldHandleOutOfBoundSlices() throws Exception {
    String sourceJson = """
        {
            "numbers": [1, 2, 3, 4, 5]
        }
        """;

    String mappingJson = """
        {
            "outOfBounds": "$.numbers[10:]"
        }
        """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    JsonNode outOfBounds = result.get("outOfBounds");
    assertThat(outOfBounds).isNotNull().isInstanceOf(ArrayNode.class);
    assertThat(outOfBounds.size()).isEqualTo(0); // Should return an empty array
  }

  @Test
  @DisplayName("Should handle dynamic field names in JsonPath using preprocessing")
  void shouldHandleDynamicFieldNames() throws Exception {
    String sourceJson = """
        {
            "field-1": "value1",
            "field-2": "value2",
            "field-3": "value3"
        }
        """;

    // Preprocess mapping JSON to replace placeholders
    String mappingJsonTemplate = """
        {
            "dynamicField1": "$.field-%d",
            "dynamicField2": "$.field-%d"
        }
        """;
    String mappingJson = String.format(mappingJsonTemplate, 1, 2);

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("dynamicField1").asText()).isEqualTo("value1");
    assertThat(result.get("dynamicField2").asText()).isEqualTo("value2");
  }

  @Test
  @DisplayName("Should handle very long field names")
  void shouldHandleVeryLongFieldNames() throws Exception {
    // Create source JSON with long field name
    StringBuilder longFieldName = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      longFieldName.append("a");
    }

    String sourceJson = String.format("""
            {
                "%s": "value"
            }
            """, longFieldName);

    String mappingJson = String.format("""
            {
                "result": "$.%s"
            }
            """, longFieldName);

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    assertThat(result.get("result").asText()).isEqualTo("value");
  }

  @Test
  @DisplayName("Should handle nested arrays with identical elements")
  void shouldHandleNestedArraysWithIdenticalElements() throws Exception {
    String sourceJson = """
            {
                "array": [
                    [1, 1, 1],
                    [null, null],
                    ["test", "test", "test"]
                ]
            }
            """;

    String mappingJson = """
            {
                "numbers": "$.array[0]",
                "nulls": "$.array[1]",
                "strings": "$.array[2]"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    JsonNode numbers = result.get("numbers");
    assertThat(numbers).isNotNull().isInstanceOf(ArrayNode.class);
    assertThat(numbers.size()).isEqualTo(3);
    assertThat(numbers.get(0).asInt()).isEqualTo(1);
    assertThat(numbers.get(1).asInt()).isEqualTo(1);
    assertThat(numbers.get(2).asInt()).isEqualTo(1);

    JsonNode nulls = result.get("nulls");
    assertThat(nulls).isNotNull().isInstanceOf(ArrayNode.class);
    assertThat(nulls.size()).isEqualTo(2);
    assertThat(nulls.get(0).isNull()).isTrue();
    assertThat(nulls.get(1).isNull()).isTrue();
  }

  @Test
  @DisplayName("Should handle empty strings in different contexts")
  void shouldHandleEmptyStrings() throws Exception {
    String sourceJson = """
            {
                "emptyString": "",
                "arrayWithEmpty": ["", "notEmpty", ""],
                "objectWithEmpty": {
                    "empty": "",
                    "nested": {
                        "empty": ""
                    }
                }
            }
            """;

    String mappingJson = """
            {
                "direct": "$.emptyString",
                "array": "$.arrayWithEmpty",
                "nested": "$.objectWithEmpty.nested.empty"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("direct").asText()).isEmpty();

    JsonNode array = result.get("array");
    assertThat(array.get(0).asText()).isEmpty();
    assertThat(array.get(1).asText()).isEqualTo("notEmpty");
    assertThat(array.get(2).asText()).isEmpty();

    assertThat(result.get("nested").asText()).isEmpty();
  }

  @Test
  @DisplayName("Should handle Unicode characters in field names")
  void shouldHandleUnicodeFieldNames() throws Exception {
    String sourceJson = """
            {
                "λ": "lambda",
                "π": 3.14159,
                "σ": ["sigma"],
                "你好": {
                    "世界": true
                }
            }
            """;

    String mappingJson = """
            {
                "lambda": "$.λ",
                "pi": "$.π",
                "sigma": "$.σ[0]",
                "chinese": "$.你好.世界"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("lambda").asText()).isEqualTo("lambda");
    assertThat(result.get("pi").asDouble()).isEqualTo(3.14159);
    assertThat(result.get("sigma").asText()).isEqualTo("sigma");
    assertThat(result.get("chinese").asBoolean()).isTrue();
  }

}
