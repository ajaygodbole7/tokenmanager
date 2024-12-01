package org.jsonmapper.feature;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
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

@DisplayName("Value Mapping Tests in JsonMapper Transform")
@Execution(ExecutionMode.CONCURRENT)
class ValueMapperTest {

  private static final ConcurrentHashMap<Long, JsonMapper> JSON_MAPPER_STORE =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<Long, ObjectMapper> OBJECT_MAPPER_STORE =
      new ConcurrentHashMap<>();

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

  @Test
  @DisplayName("Should use default value for missing source field")
  void shouldUseDefaultValueForMissingSourceField() throws Exception {
    String sourceJson =
        """
                {
                    "type": "basic"
                }
                """;
    String mappingJson =
        """
                {
                    "mappedCategory": {
                        "type": "value",
                        "sourcePath": "$.category",
                        "mappings": [
                            { "source": "premium", "target": "gold" },
                            { "source": "basic", "target": "silver" }
                        ],
                        "default": "bronze"
                    }
                }
                """;

    JsonNode mappingRules = objectMapper.readTree(mappingJson);
    JsonNode result = jsonMapper.transform(sourceJson, mappingRules);

    assertThat(result.get("mappedCategory").asText()).isEqualTo("bronze");
  }

  @Test
  @DisplayName("Should map null source value to default value")
  void shouldMapNullSourceValueToDefaultValue() throws Exception {
    String sourceJson =
        """
                {
                    "category": null
                }
                """;
    String mappingJson =
        """
                {
                    "mappedCategory": {
                        "type": "value",
                        "sourcePath": "$.category",
                        "mappings": [
                            { "source": "premium", "target": "gold" },
                            { "source": "basic", "target": "silver" }
                        ],
                        "default": "bronze"
                    }
                }
                """;

    JsonNode mappingRules = objectMapper.readTree(mappingJson);
    JsonNode result = jsonMapper.transform(sourceJson, mappingRules);

    assertThat(result.get("mappedCategory").asText()).isEqualTo("bronze");
  }

  @Test
  @DisplayName("Should map value based on explicit mappings")
  void shouldMapValueBasedOnExplicitMappings() throws Exception {
    String sourceJson =
        """
            {
                "category": "premium"
            }
            """;
    String mappingJson =
        """
            {
                "mappedCategory": {
                    "type": "value",
                    "sourcePath": "$.category",
                    "mappings": [
                        { "source": "premium", "target": "gold" },
                        { "source": "basic", "target": "silver" }
                    ],
                    "default": "bronze"
                }
            }
            """;

    JsonNode mappingRules = objectMapper.readTree(mappingJson);
    JsonNode result = jsonMapper.transform(sourceJson, mappingRules);

    System.out.println("Transformed JSON: " + result.toPrettyString());
    assertThat(result.get("mappedCategory").asText()).isEqualTo("gold");
  }

  @Test
  @DisplayName("Should map nested value to target field")
  void shouldMapNestedValueToTargetField() throws Exception {
    String sourceJson =
        """
            {
                "details": {
                    "category": "premium"
                }
            }
            """;
    String mappingJson =
        """
            {
                "mappedCategory": {
                    "type": "value",
                    "sourcePath": "$.details.category",
                    "mappings": [
                        { "source": "premium", "target": "gold" },
                        { "source": "basic", "target": "silver" }
                    ],
                    "default": "bronze"
                }
            }
            """;

    JsonNode mappingRules = objectMapper.readTree(mappingJson);
    JsonNode result = jsonMapper.transform(sourceJson, mappingRules);

    assertThat(result.get("mappedCategory").asText()).isEqualTo("gold");
  }

  @Test
  @DisplayName("Should map array values correctly")
  void shouldMapArrayValuesCorrectly() throws Exception {
    String sourceJson =
        """
            {
                "categories": ["premium", "basic", "unknown"]
            }
            """;
    String mappingJson =
        """
            {
                "mappedCategories": {
                    "type": "value",
                    "sourcePath": "$.categories",
                    "mappings": [
                        { "source": "premium", "target": "gold" },
                        { "source": "basic", "target": "silver" }
                    ],
                    "default": "bronze"
                }
            }
            """;

    JsonNode mappingRules = objectMapper.readTree(mappingJson);
    JsonNode result = jsonMapper.transform(sourceJson, mappingRules);

    System.out.println("Transformed JSON: " + result.toPrettyString());

    // Extract values from the array
    List<String> mappedCategories = new ArrayList<>();
    result.get("mappedCategories").forEach(node -> mappedCategories.add(node.asText()));

    assertThat(mappedCategories).containsExactly("gold", "silver", "bronze");
  }

  @Test
  @DisplayName("Should handle invalid JsonPath")
  void shouldThrowExceptionForInvalidJsonPath() {
    String sourceJson =
        """
            {
                "category": "premium"
            }
            """;
    String mappingJson =
        """
            {
                "mappedCategory": {
                    "type": "value",
                    "sourcePath": "$.[invalidPath]",
                    "default": "bronze"
                }
            }
            """;

    JsonNode mappingRules;
    try {
      mappingRules = objectMapper.readTree(mappingJson);
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse JSON", e);
    }

    Throwable thrown = catchThrowable(() -> jsonMapper.transform(sourceJson, mappingRules));

    assertThat(thrown)
        .isInstanceOf(JsonTransformationException.class)
        .hasMessageContaining("Failed to process mapping for field: mappedCategory");
  }

  @Test
  @DisplayName("Should map deeply nested value")
  void shouldMapDeeplyNestedValue() throws Exception {
    String sourceJson =
        """
            {
                "data": {
                    "details": {
                        "category": "basic"
                    }
                }
            }
            """;
    String mappingJson =
        """
            {
                "mappedCategory": {
                    "type": "value",
                    "sourcePath": "$.data.details.category",
                    "mappings": [
                        { "source": "premium", "target": "gold" },
                        { "source": "basic", "target": "silver" }
                    ],
                    "default": "bronze"
                }
            }
            """;

    JsonNode mappingRules = objectMapper.readTree(mappingJson);
    JsonNode result = jsonMapper.transform(sourceJson, mappingRules);

    assertThat(result.get("mappedCategory").asText()).isEqualTo("silver");
  }

  @Test
  @DisplayName("Should throw exception when mappings are missing")
  void shouldThrowExceptionWhenMappingsAreMissing() {
    String sourceJson =
        """
            {
                "category": "directValue"
            }
            """;
    String mappingJson =
        """
            {
                "mappedCategory": {
                    "type": "value",
                    "sourcePath": "$.category"
                }
            }
            """;

    JsonNode mappingRules;
    try {
      mappingRules = objectMapper.readTree(mappingJson);
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse JSON", e);
    }

    Throwable thrown = catchThrowable(() -> jsonMapper.transform(sourceJson, mappingRules));

    assertThat(thrown)
        .isInstanceOf(JsonTransformationException.class)
        .hasMessageContaining("Failed to process mapping for field: mappedCategory");
    // org.jsonmapper.JsonTransformationException: Failed to process mapping for field:
    // mappedCategory
  }

  @Test
  @DisplayName("Should process hierarchical array elements in shopping cart")
  void shouldProcessHierarchicalArrayElements() throws Exception {
    String sourceJson = """
                {
                    "cart": {
                        "items": [
                            { "id": 1, "name": "Laptop", "category": "electronics" },
                            { "id": 2, "name": "Shirt", "category": "apparel" },
                            { "id": 3, "name": "Apple", "category": "groceries" }
                        ]
                    }
                }
                """;

    String mappingJson = """
                {
                   "cart": {
                     "type": "array",
                     "sourcePath": "$.cart.items",
                     "itemMapping": {
                       "id": "$.id",
                       "name": "$.name",
                       "category": {
                         "type": "value",
                         "sourcePath": "$.category",
                         "mappings": [
                           { "source": "electronics", "target": "Electronics & Gadgets" },
                           { "source": "apparel", "target": "Clothing" },
                           { "source": "groceries", "target": "Fruits & Vegetables" }
                         ],
                         "default": "Others"
                       }
                     }
                   }
                }
                """;

    JsonNode mappingRules = objectMapper.readTree(mappingJson);
    JsonNode result = jsonMapper.transform(sourceJson, mappingRules);

    System.out.println("Transformed JSON: " + result.toPrettyString());

    // Assertions
    JsonNode items = result.get("cart");
    assertThat(items.size()).isEqualTo(3); // Ensure all items are processed
    assertThat(items.get(0).get("category").asText()).isEqualTo("Electronics & Gadgets");
    assertThat(items.get(1).get("category").asText()).isEqualTo("Clothing");
    assertThat(items.get(2).get("category").asText()).isEqualTo("Fruits & Vegetables");
  }

}
