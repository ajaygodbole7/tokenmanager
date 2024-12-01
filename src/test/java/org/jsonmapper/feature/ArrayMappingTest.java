package org.jsonmapper.feature;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

@DisplayName("Array Mapping Tests")
@Execution(ExecutionMode.CONCURRENT)
class ArrayMappingTest {
  private static final ConcurrentHashMap<Long, JsonMapper> JSON_MAPPER_STORE = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<Long, ObjectMapper> OBJECT_MAPPER_STORE = new ConcurrentHashMap<>();

  private JsonMapper jsonMapper;
  private ObjectMapper objectMapper;

  private static final String SHOPPING_CART_JSON = """
            {
                "cart": [
                    {"id": 101, "name": "Laptop", "price": 999.99, "quantity": 1},
                    {"id": 102, "name": "Mouse", "price": 49.99, "quantity": 2},
                    {"id": 103, "name": "Keyboard", "price": 199.99, "quantity": 1}
                ]
            }
            """;

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

  @Test
  @DisplayName("Should map simple array successfully")
  void shouldMapSimpleArray() throws Exception {
    String sourceJson = """
            {
                "numbers": [1, 2, 3, 4, 5]
            }
            """;

    String mappingJson = """
            {
                "result": "$.numbers"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("result")).isNotNull().isInstanceOf(ArrayNode.class);
    assertThat(result.get("result").size()).isEqualTo(5);
    assertThat(result.get("result").get(0).asInt()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should handle empty array")
  void shouldHandleEmptyArray() throws Exception {
    String sourceJson = """
            {
                "empty": []
            }
            """;

    String mappingJson = """
            {
                "result": "$.empty"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("result")).isNotNull().isInstanceOf(ArrayNode.class);
    assertThat(result.get("result").size()).isEqualTo(0);
  }

  @Test
  @DisplayName("Should handle array elements with different types")
  void shouldHandleArrayWithMixedTypes() throws Exception {
    String sourceJson = """
            {
                "mixed": [1, "text", true, null, {"key": "value"}]
            }
            """;

    String mappingJson = """
            {
                "result": "$.mixed"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    JsonNode array = result.get("result");

    assertThat(array).isNotNull().isInstanceOf(ArrayNode.class);
    assertThat(array.size()).isEqualTo(5);
    assertThat(array.get(0).asInt()).isEqualTo(1);
    assertThat(array.get(1).asText()).isEqualTo("text");
    assertThat(array.get(2).asBoolean()).isTrue();
    assertThat(array.get(3).isNull()).isTrue();
    assertThat(array.get(4).get("key").asText()).isEqualTo("value");
  }

  @Test
  @DisplayName("Should access array elements by index")
  void shouldAccessArrayElementsByIndex() throws Exception {
    String sourceJson = """
            {
                "array": [10, 20, 30, 40, 50]
            }
            """;

    String mappingJson = """
            {
                "first": "$.array[0]",
                "third": "$.array[2]",
                "last": "$.array[-1]"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("first").asInt()).isEqualTo(10);
    assertThat(result.get("third").asInt()).isEqualTo(30);
    assertThat(result.get("last").asInt()).isEqualTo(50);
  }

  @Test
  @DisplayName("Should handle array slicing")
  void shouldHandleArraySlicing() throws Exception {
    String sourceJson = """
            {
                "array": [1, 2, 3, 4, 5]
            }
            """;

    String mappingJson = """
            {
                "firstTwo": "$.array[0:2]",
                "lastTwo": "$.array[-2:]",
                "middle": "$.array[1:-1]"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    ArrayNode firstTwo = (ArrayNode) result.get("firstTwo");
    assertThat(firstTwo.size()).isEqualTo(2);
    assertThat(firstTwo.get(0).asInt()).isEqualTo(1);
    assertThat(firstTwo.get(1).asInt()).isEqualTo(2);

    ArrayNode lastTwo = (ArrayNode) result.get("lastTwo");
    assertThat(lastTwo.size()).isEqualTo(2);
    assertThat(lastTwo.get(0).asInt()).isEqualTo(4);
    assertThat(lastTwo.get(1).asInt()).isEqualTo(5);
  }

  @Test
  @DisplayName("Should filter array elements based on condition")
  void shouldFilterArrayElements() throws Exception {
    String sourceJson = """
            {
                "items": [
                    {"id": 1, "value": 10, "active": true},
                    {"id": 2, "value": 20, "active": false},
                    {"id": 3, "value": 30, "active": true}
                ]
            }
            """;

    String mappingJson = """
            {
                "activeItems": "$.items[?(@.active == true)]",
                "highValue": "$.items[?(@.value > 15)]"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    ArrayNode activeItems = (ArrayNode) result.get("activeItems");
    assertThat(activeItems.size()).isEqualTo(2);
    assertThat(activeItems.get(0).get("id").asInt()).isEqualTo(1);
    assertThat(activeItems.get(1).get("id").asInt()).isEqualTo(3);

    ArrayNode highValue = (ArrayNode) result.get("highValue");
    assertThat(highValue.size()).isEqualTo(2);
    assertThat(highValue.get(0).get("value").asInt()).isEqualTo(20);
  }

  @Test
  @DisplayName("Should handle out of bounds array access")
  void shouldHandleOutOfBoundsArrayAccess() throws Exception {
    String sourceJson = """
            {
                "array": [1, 2, 3]
            }
            """;

    String mappingJson = """
            {
                "outOfBounds": "$.array[5]",
                "slice": "$.array[3:10]"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    assertThat(result.get("outOfBounds")).isNotNull();
    assertThat(result.get("outOfBounds").isNull()).isTrue();

    ArrayNode slice = (ArrayNode) result.get("slice");
    assertThat(slice.size()).isEqualTo(0);
  }

  @Test
  @DisplayName("Should handle invalid array path")
  void shouldHandleInvalidArrayPath() {
    String sourceJson = """
            {
                "array": [1, 2, 3]
            }
            """;

    String mappingJson = """
            {
                "result": "$.array[invalid]"
            }
            """;

    assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class);
  }

  @Test
  @DisplayName("Should handle null array elements")
  void shouldHandleNullArrayElements() throws Exception {
    String sourceJson = """
            {
                "array": [null, 1, null, 2, null]
            }
            """;

    String mappingJson = """
            {
                "result": "$.array"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    ArrayNode array = (ArrayNode) result.get("result");

    assertThat(array.size()).isEqualTo(5);
    assertThat(array.get(0).isNull()).isTrue();
    assertThat(array.get(1).asInt()).isEqualTo(1);
    assertThat(array.get(2).isNull()).isTrue();
  }

  @Test
  @DisplayName("Should map shopping cart items")
  void shouldMapShoppingCartItems() throws Exception {
    String mappingJson = """
                {
                    "items": "$.cart"
                }
                """;

    JsonNode result = jsonMapper.transform(SHOPPING_CART_JSON, getCleanJson(mappingJson));
    ArrayNode items = (ArrayNode) result.get("items");

    assertThat(items.size()).isEqualTo(3);
    assertThat(items.get(0).get("id").asInt()).isEqualTo(101);
    assertThat(items.get(0).get("name").asText()).isEqualTo("Laptop");
    assertThat(items.get(0).get("price").asDouble()).isEqualTo(999.99);
    assertThat(items.get(0).get("quantity").asInt()).isEqualTo(1);
  }

   @Test
  @DisplayName("Should filter cart items by price")
  void shouldFilterCartItemsByPrice() throws Exception {
    String mappingJson = """
                {
                    "expensiveItems": "$.cart[?(@.price > 100)]"
                }
                """;

    JsonNode result = jsonMapper.transform(SHOPPING_CART_JSON, getCleanJson(mappingJson));
    ArrayNode expensiveItems = (ArrayNode) result.get("expensiveItems");

    assertThat(expensiveItems.size()).isEqualTo(2);
    assertThat(expensiveItems.get(0).get("name").asText()).isEqualTo("Laptop");
    assertThat(expensiveItems.get(1).get("name").asText()).isEqualTo("Keyboard");
  }

  @Test
  @DisplayName("Should filter items based on a single condition")
  void shouldFilterItemsByPriceGreaterThan100() throws Exception {
    String mappingJson = """
            {
                "filteredItems": "$.cart[?(@.price > 100)]"
            }
            """;

    JsonNode result = jsonMapper.transform(SHOPPING_CART_JSON, getCleanJson(mappingJson));
    ArrayNode filteredItems = (ArrayNode) result.get("filteredItems");

    // Assertions
    assertThat(filteredItems.size()).isEqualTo(2); // Matches Laptop and Keyboard
    assertThat(filteredItems.get(0).get("name").asText()).isEqualTo("Laptop");
    assertThat(filteredItems.get(1).get("name").asText()).isEqualTo("Keyboard");
  }

  @Test
  @DisplayName("Should throw exception for unsupported operations")
  void shouldThrowForUnsupportedOperations() {
    String mappingJson = """
            {
                "result": "$.cart.unsupportedFunction()"
            }
            """;

    assertThatThrownBy(() -> jsonMapper.transform(SHOPPING_CART_JSON, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class);
  }


  @Test
  @DisplayName("Should handle nested arrays")
  void shouldHandleNestedArrays() throws Exception {
    String sourceJson = """
            {
                "nestedArray": [[1, 2], [3, 4], [5, 6]]
            }
            """;

    String mappingJson = """
            {
                "flattened": "$.nestedArray[*][*]"
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    ArrayNode flattened = (ArrayNode) result.get("flattened");

    assertThat(flattened.size()).isEqualTo(6);
    assertThat(flattened.get(0).asInt()).isEqualTo(1);
    assertThat(flattened.get(5).asInt()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should handle null input JSON")
  void shouldHandleNullInputJson() {
    assertThatThrownBy(() -> jsonMapper.transform(null, getCleanJson("{}")))
        .isInstanceOf(JsonTransformationException.class)
        .hasMessageContaining("Source JSON cannot be null");
  }


  @Test
  @DisplayName("Should handle complex nested array mapping with value and conditional transformations")
  void shouldHandleComplexNestedArrayMapping() throws Exception {
    String sourceJson = """
            {
                "order": {
                    "id": "ORD-123",
                    "items": [
                        {
                            "id": "ITEM-1",
                            "product": {
                                "name": "Premium Laptop",
                                "category": "electronics",
                                "price": 1299.99,
                                "specs": {
                                    "warranty": "2 years",
                                    "condition": "new"
                                }
                            },
                            "quantity": 1,
                            "status": "in_stock"
                        },
                        {
                            "id": "ITEM-2",
                            "product": {
                                "name": "Wireless Mouse",
                                "category": "accessories",
                                "price": 49.99,
                                "specs": {
                                    "warranty": "1 year",
                                    "condition": "new"
                                }
                            },
                            "quantity": 2,
                            "status": "in_stock"
                        }
                    ],
                    "shipping": {
                        "method": "express",
                        "price": 15.99
                    }
                }
            }
            """;

    String mappingJson = """
            {
                "cart": {
                    "type": "array",
                    "sourcePath": "$.order.items",
                    "itemMapping": {
                        "id": "$.id",
                        "name": "$.product.name",
                        "category": {
                            "type": "value",
                            "sourcePath": "$.product.category",
                            "mappings": [
                                {"source": "electronics", "target": "Electronics & Gadgets"},
                                {"source": "accessories", "target": "Computer Accessories"}
                            ],
                            "default": "Other"
                        },
                        "price": "$.product.price",
                        "quantity": "$.quantity",
                        "warranty": "$.product.specs.warranty",
                        "priceType": {
                            "type": "conditional",
                            "conditions": [
                                {
                                    "path": "$.product.price",
                                    "operator": "gt",
                                    "value": 1000,
                                    "result": "Premium"
                                },
                                {
                                    "path": "$.product.price",
                                    "operator": "gt",
                                    "value": 50,
                                    "result": "Standard"
                                }
                            ],
                            "default": "Budget"
                        },
                        "total": {
                            "type": "function",
                            "function": "$number",
                            "sourcePath": "$.product.price"
                        }
                    }
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    // Check the results
    JsonNode items = result.get("cart");
    assertThat(items.size()).isEqualTo(2); // Ensure all items are processed

    // Verify first item (Laptop)
    JsonNode firstItem = items.get(0);
    assertThat(firstItem.get("id").asText()).isEqualTo("ITEM-1");
    assertThat(firstItem.get("name").asText()).isEqualTo("Premium Laptop");
    assertThat(firstItem.get("category").asText()).isEqualTo("Electronics & Gadgets");
    assertThat(firstItem.get("price").asDouble()).isEqualTo(1299.99);
    assertThat(firstItem.get("quantity").asInt()).isEqualTo(1);
    assertThat(firstItem.get("warranty").asText()).isEqualTo("2 years");
    assertThat(firstItem.get("priceType").asText()).isEqualTo("Premium");
    assertThat(firstItem.get("total").asDouble()).isEqualTo(1299.99);

    // Verify second item (Mouse)
    JsonNode secondItem = items.get(1);
    assertThat(secondItem.get("id").asText()).isEqualTo("ITEM-2");
    assertThat(secondItem.get("name").asText()).isEqualTo("Wireless Mouse");
    assertThat(secondItem.get("category").asText()).isEqualTo("Computer Accessories");
    assertThat(secondItem.get("price").asDouble()).isEqualTo(49.99);
    assertThat(secondItem.get("quantity").asInt()).isEqualTo(2);
    assertThat(secondItem.get("warranty").asText()).isEqualTo("1 year");
    assertThat(secondItem.get("priceType").asText()).isEqualTo("Budget");
    assertThat(secondItem.get("total").asDouble()).isEqualTo(49.99);
  }

  /**
   * Tests array mapping with Order and OrderLines structure.
   * Demonstrates:
   * - Nested array mapping (order -> orderLines)
   * - Value mappings for item categories
   * - Simple conditional based on total order amount
   */
  @Test
  @DisplayName("Should handle order with orderLines array mapping")
  void shouldHandleOrderWithOrderLines() throws Exception {
    String sourceJson = """
            {
                "orders": [
                    {
                        "id": "ORD-1",
                        "orderDate": "2024-03-15",
                        "orderLines": [
                            {
                                "lineId": "LINE-1",
                                "product": {
                                    "id": "PROD-1",
                                    "name": "Laptop",
                                    "category": "electronics"
                                },
                                "quantity": 2,
                                "unitPrice": 1200.00,
                                "lineTotal": 2400.00
                            },
                            {
                                "lineId": "LINE-2",
                                "product": {
                                    "id": "PROD-2",
                                    "name": "Mouse",
                                    "category": "accessories"
                                },
                                "quantity": 3,
                                "unitPrice": 25.00,
                                "lineTotal": 75.00
                            }
                        ],
                        "totalAmount": 2475.00
                    },
                    {
                        "id": "ORD-2",
                        "orderDate": "2024-03-15",
                        "orderLines": [
                            {
                                "lineId": "LINE-3",
                                "product": {
                                    "id": "PROD-3",
                                    "name": "USB Cable",
                                    "category": "accessories"
                                },
                                "quantity": 1,
                                "unitPrice": 15.00,
                                "lineTotal": 15.00
                            }
                        ],
                        "totalAmount": 15.00
                    }
                ]
            }
            """;

    String mappingJson = """
            {
                "orders": {
                    "type": "array",
                    "sourcePath": "$.orders",
                    "itemMapping": {
                        "orderId": "$.id",
                        "orderDate": "$.orderDate",
                        "orderAmount": "$.totalAmount",
                        "orderType": {
                            "type": "conditional",
                            "conditions": [
                                {
                                    "path": "$.totalAmount",
                                    "operator": "gt",
                                    "value": 1000,
                                    "result": "HIGH_VALUE"
                                }
                            ],
                            "default": "STANDARD"
                        },
                        "lines": {
                            "type": "array",
                            "sourcePath": "$.orderLines",
                            "itemMapping": {
                                "id": "$.lineId",
                                "productId": "$.product.id",
                                "productName": "$.product.name",
                                "category": {
                                    "type": "value",
                                    "sourcePath": "$.product.category",
                                    "mappings": [
                                        {"source": "electronics", "target": "Electronics & Gadgets"},
                                        {"source": "accessories", "target": "Computer Accessories"}
                                    ]
                                },
                                "quantity": "$.quantity",
                                "price": "$.unitPrice",
                                "total": "$.lineTotal"
                            }
                        }
                    }
                }
            }
            """;

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));
    ArrayNode orders = (ArrayNode) result.get("orders");

    // Verify array size
    assertThat(orders.size()).isEqualTo(2);

    // Verify first order (High value order)
    JsonNode firstOrder = orders.get(0);
    assertThat(firstOrder.get("orderId").asText()).isEqualTo("ORD-1");
    assertThat(firstOrder.get("orderAmount").asDouble()).isEqualTo(2475.00);
    assertThat(firstOrder.get("orderType").asText()).isEqualTo("HIGH_VALUE");

    // Verify first order lines
    ArrayNode firstOrderLines = (ArrayNode) firstOrder.get("lines");
    assertThat(firstOrderLines.size()).isEqualTo(2);

    JsonNode laptopLine = firstOrderLines.get(0);
    assertThat(laptopLine.get("productName").asText()).isEqualTo("Laptop");
    assertThat(laptopLine.get("category").asText()).isEqualTo("Electronics & Gadgets");
    assertThat(laptopLine.get("quantity").asInt()).isEqualTo(2);
    assertThat(laptopLine.get("total").asDouble()).isEqualTo(2400.00);

    JsonNode mouseLine = firstOrderLines.get(1);
    assertThat(mouseLine.get("category").asText()).isEqualTo("Computer Accessories");
    assertThat(mouseLine.get("total").asDouble()).isEqualTo(75.00);

    // Verify second order (Standard order)
    JsonNode secondOrder = orders.get(1);
    assertThat(secondOrder.get("orderId").asText()).isEqualTo("ORD-2");
    assertThat(secondOrder.get("orderAmount").asDouble()).isEqualTo(15.00);
    assertThat(secondOrder.get("orderType").asText()).isEqualTo("STANDARD");

    ArrayNode secondOrderLines = (ArrayNode) secondOrder.get("lines");
    assertThat(secondOrderLines.size()).isEqualTo(1);

    JsonNode cableLine = secondOrderLines.get(0);
    assertThat(cableLine.get("productName").asText()).isEqualTo("USB Cable");
    assertThat(cableLine.get("category").asText()).isEqualTo("Computer Accessories");
    assertThat(cableLine.get("total").asDouble()).isEqualTo(15.00);
  }
}
