package org.jsonmapper.feature;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ConcurrentHashMap;
import org.jsonmapper.JsonMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@DisplayName("Conditional Functions Tests")
@Execution(ExecutionMode.CONCURRENT)
public class ContainsConditonalTest {

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

  //CONTAINS OPERATOR TESTS
  @Test
  @DisplayName("[CONTAINS] Should match when string contains substring")
  void containsOperator_ShouldMatchSubstring() throws Exception {
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
                            "operator": "contains",
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
  @DisplayName("[CONTAINS] Should not match when string doesn't contain substring")
  void containsOperator_ShouldNotMatchMissingSubstring() throws Exception {
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
                            "operator": "contains",
                            "value": "Universe",
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
  @DisplayName("[CONTAINS] Should handle case sensitivity")
  void containsOperator_ShouldHandleCaseSensitivity() throws Exception {
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
                            "operator": "contains",
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

  @Test
  @DisplayName("[CONTAINS] Should match empty string")
  void containsOperator_ShouldMatchEmptyString() throws Exception {
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
                            "operator": "contains",
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
  @DisplayName("[CONTAINS] Should handle null value in source")
  void containsOperator_ShouldHandleNullValue() throws Exception {
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
                            "operator": "contains",
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
  @DisplayName("[CONTAINS] Should handle missing field")
  void containsOperator_ShouldHandleMissingField() throws Exception {
    String sourceJson = """
            {
                "other": "value"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "contains",
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
  @DisplayName("[CONTAINS] Should handle special characters")
  void containsOperator_ShouldHandleSpecialCharacters() throws Exception {
    String sourceJson = """
            {
                "text": "Hello!@#$%^&*()_+ World"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "contains",
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
  @DisplayName("[CONTAINS] Should handle unicode characters")
  void containsOperator_ShouldHandleUnicodeCharacters() throws Exception {
    String sourceJson = """
            {
                "text": "Hello 世界 World"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "contains",
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



}
