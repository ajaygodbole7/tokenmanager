package org.jsonmapper.integration;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsonmapper.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Shopify Cart Integration Test")
class ShopifyCartIntegrationTest {

  /*
  private JsonMapper jsonMapper;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    jsonMapper = new JsonMapper();
    objectMapper = new ObjectMapper();
  }

  @Test
  @DisplayName("Should transform Shopify cart to normalized format")
  void shouldTransformShopifyCart() throws Exception {
    // Given
    String sourceJson = loadResource("/shopify-cart.json");
    String mappingJson = loadResource("/shopify-cart-mapping.json");

    // When
    JsonNode result = jsonMapper.transform(sourceJson, objectMapper.readTree(mappingJson));

    // Write the transformed output to a file
    writeOutputToFile(result, "src/test/resources/transformed-cart.json");

    // Then
    assertThat(result).isNotNull();

    // Cart level assertions
    assertThat(result.get("cartId").asText()).isNotEmpty();
    assertThat(result.get("cartNote").asText()).isEqualTo("Hello!");
    assertThat(result.get("currency").asText()).isEqualTo("CAD");
    assertThat(result.get("totalAmount").asDouble()).isEqualTo(2925.0);
    assertThat(result.get("discountAmount").asDouble()).isEqualTo(474.0);
    assertThat(result.get("itemCount").asInt()).isEqualTo(2);
    assertThat(result.get("requiresShipping").asBoolean()).isTrue();

    // Cart items assertions
    JsonNode items = result.get("items");
    assertThat(items.isArray()).isTrue();
    assertThat(items.size()).isEqualTo(2);

    // First item assertions
    JsonNode firstItem = items.get(0);
    assertThat(firstItem.get("id").asText()).isEqualTo("39897499729985");
    assertThat(firstItem.get("title").asText()).isEqualTo("Health potion");
    assertThat(firstItem.get("variant").asText()).isEqualTo("S / Low");
    assertThat(firstItem.get("quantity").asInt()).isEqualTo(1);
    assertThat(firstItem.get("unitPrice").asDouble()).isEqualTo(900.0);
    assertThat(firstItem.get("totalPrice").asDouble()).isEqualTo(900.0);
    assertThat(firstItem.get("isGiftCard").asBoolean()).isFalse();
    assertThat(firstItem.get("productType").asText()).isEqualTo("Health");

    // Discounts array for first item
    JsonNode firstItemDiscounts = firstItem.get("discounts");
    assertThat(firstItemDiscounts.isArray()).isTrue();
    assertThat(firstItemDiscounts.size()).isEqualTo(0);

    // Second item assertions
    JsonNode secondItem = items.get(1);
    assertThat(secondItem.get("id").asText()).isEqualTo("39888235757633");
    assertThat(secondItem.get("title").asText()).isEqualTo("Whole bloodroot");
    assertThat(secondItem.get("quantity").asInt()).isEqualTo(1);
    assertThat(secondItem.get("unitPrice").asDouble()).isEqualTo(2499.0);
    assertThat(secondItem.get("totalPrice").asDouble()).isEqualTo(2025.0);

    // Discounts array for second item
    JsonNode secondItemDiscounts = secondItem.get("discounts");
    assertThat(secondItemDiscounts.isArray()).isTrue();
    assertThat(secondItemDiscounts.size()).isEqualTo(2);
    assertThat(secondItemDiscounts.get(0).get("title").asText()).isEqualTo("Bloodroot discount!");
    assertThat(secondItemDiscounts.get(0).get("amount").asDouble()).isEqualTo(250.0);
    assertThat(secondItemDiscounts.get(1).get("title").asText()).isEqualTo("Ingredient Sale");
    assertThat(secondItemDiscounts.get(1).get("amount").asDouble()).isEqualTo(224.0);
  }

  private String loadResource(String path) throws Exception {
    return new String(getClass().getResourceAsStream(path).readAllBytes());
  }

  private void writeOutputToFile(JsonNode jsonNode, String outputPath) throws Exception {
    // Create output directory if it doesn't exist
    java.nio.file.Files.createDirectories(java.nio.file.Paths.get("src/test/resources"));

    // Write the JSON with pretty printing
    objectMapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(new java.io.File(outputPath), jsonNode);

    System.out.println("Transformed output has been written to: " + outputPath);
  }

   */
}
