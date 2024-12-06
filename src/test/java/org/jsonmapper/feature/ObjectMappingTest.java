package org.jsonmapper.feature;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsonmapper.JsonMapper;
import org.jsonmapper.JsonTransformationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@DisplayName("Object Mapping Tests")
@Execution(ExecutionMode.CONCURRENT)
public class ObjectMappingTest {

  private static final ThreadLocal<JsonMapper> JSON_MAPPER = ThreadLocal.withInitial(JsonMapper::new);
  private static final ThreadLocal<ObjectMapper> OBJECT_MAPPER = ThreadLocal.withInitial(ObjectMapper::new);

  @AfterEach
  void tearDown() {
    JSON_MAPPER.remove();
    OBJECT_MAPPER.remove();
  }

  @AfterAll
  static void cleanUpAll() {
    JSON_MAPPER.remove();
    OBJECT_MAPPER.remove();
  }

  private JsonNode getCleanJson(String json) throws Exception {
    return OBJECT_MAPPER.get().readTree(json);
  }

  @Test
  @DisplayName("[OBJECT] Should successfully map nested object")
  void objectMapping_ShouldMapNestedObject() throws Exception {
    String sourceJson = """
            {
                "details": {
                    "id": "12345",
                    "name": {
                        "firstName": "John",
                        "lastName": "Doe"
                    },
                    "address": {
                        "city": "New York",
                        "state": "NY"
                    }
                }
            }
        """;

    String mappingJson = """
            {
                "result": {
                    "type": "object",
                    "userId": "$.details.id",
                    "fullName": {
                        "type": "object",
                        "first": "$.details.name.firstName",
                        "last": "$.details.name.lastName"
                    },
                    "location": {
                        "type": "object",
                        "city": "$.details.address.city",
                        "state": "$.details.address.state"
                    }
                }
            }
        """;

    JsonNode result = JSON_MAPPER.get().transform(sourceJson, getCleanJson(mappingJson));
    JsonNode resultNode = result.get("result");

    assertThat(resultNode).isNotNull();
    assertThat(resultNode.get("userId").asText()).isEqualTo("12345");

    JsonNode fullName = resultNode.get("fullName");
    assertThat(fullName).isNotNull();
    assertThat(fullName.get("first").asText()).isEqualTo("John");
    assertThat(fullName.get("last").asText()).isEqualTo("Doe");

    JsonNode location = resultNode.get("location");
    assertThat(location).isNotNull();
    assertThat(location.get("city").asText()).isEqualTo("New York");
    assertThat(location.get("state").asText()).isEqualTo("NY");
  }

  @Test
  @DisplayName("[OBJECT] Should handle null values in source JSON")
  void objectMapping_ShouldHandleNullValues() throws Exception {
    String sourceJson = """
            {
                "details": {
                    "id": null,
                    "name": {
                        "firstName": null,
                        "lastName": "Doe"
                    },
                    "address": null
                }
            }
        """;

    String mappingJson = """
            {
                "result": {
                    "type": "object",
                    "userId": "$.details.id",
                    "fullName": {
                        "type": "object",
                        "first": "$.details.name.firstName",
                        "last": "$.details.name.lastName"
                    },
                    "location": {
                        "type": "object",
                        "city": "$.details.address.city",
                        "state": "$.details.address.state"
                    }
                }
            }
        """;

    JsonNode result = JSON_MAPPER.get().transform(sourceJson, getCleanJson(mappingJson));
    JsonNode resultNode = result.get("result");

    assertThat(resultNode).isNotNull();
    assertThat(resultNode.get("userId").isNull()).isTrue();

    JsonNode fullName = resultNode.get("fullName");
    assertThat(fullName).isNotNull();
    assertThat(fullName.get("first").isNull()).isTrue();
    assertThat(fullName.get("last").asText()).isEqualTo("Doe");

    JsonNode location = resultNode.get("location");
    assertThat(location).isNotNull();  // Ensure location exists
    assertThat(location.get("city").isNull()).isTrue();
    assertThat(location.get("state").isNull()).isTrue();
  }

  @Test
  @DisplayName("[OBJECT] Should throw exception for missing type field")
  void objectMapping_ShouldThrowExceptionForMissingType() {
    String sourceJson = """
            {
                "details": {
                    "id": "12345"
                }
            }
        """;

    String mappingJson = """
            {
                "result": {
                    "userId": "$.details.id"
                }
            }
        """;

    assertThatThrownBy(() -> JSON_MAPPER.get().transform(sourceJson, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class)
        .hasMessageContaining("Failed to process mapping for field: result");
  }

  @Test
  @DisplayName("[OBJECT] Should handle empty object mapping")
  void objectMapping_ShouldHandleEmptyMapping() throws Exception {
    String sourceJson = """
            {
                "details": {
                    "id": "12345"
                }
            }
        """;

    String mappingJson = """
            {
                "result": {
                    "type": "object"
                }
            }
        """;

    JsonNode result = JSON_MAPPER.get().transform(sourceJson, getCleanJson(mappingJson));
    JsonNode resultNode = result.get("result");

    assertThat(resultNode).isNotNull();
    assertThat(resultNode.size()).isEqualTo(0);
  }

  @Test
  @DisplayName("[OBJECT] Should handle invalid source path by resolving to null")
  void objectMapping_ShouldHandleInvalidSourcePathGracefully() throws Exception {
    String sourceJson = """
        {
            "details": {
                "id": "12345"
            }
        }
        """;

    String mappingJson = """
        {
            "result": {
                "type": "object",
                "userId": "$.invalidPath"
            }
        }
        """;

    JsonNode result = JSON_MAPPER.get().transform(sourceJson, getCleanJson(mappingJson));
    JsonNode resultNode = result.get("result");

    // Assert result is not null
    assertThat(resultNode).isNotNull();

    // Assert userId is null because the path was invalid
    assertThat(resultNode.get("userId").isNull()).isTrue();
  }

}
