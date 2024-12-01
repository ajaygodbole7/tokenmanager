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

@DisplayName("Regex Operator Conditional Tests")
@Execution(ExecutionMode.CONCURRENT)
class RegexOperatorConditionalTest {
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

  private JsonNode getCleanJson(String json) throws Exception {
    return new ObjectMapper().readTree(json);
  }

  // Positive Test Cases
  @Test
  @DisplayName("[REGEX] Should match basic pattern")
  void regexOperator_ShouldMatchBasicPattern() throws Exception {
    String sourceJson =
        """
                {
                    "text": "Hello World"
                }
                """;

    String mappingJson =
        """
                {
                    "result": {
                        "type": "conditional",
                        "conditions": [
                            {
                                "path": "$.text",
                                "operator": "regex",
                                "value": "Hello World",
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
  @DisplayName("[REGEX] Should match pattern with whitespace")
  void regexOperator_ShouldMatchWithWhitespace() throws Exception {
    String sourceJson = """
            {
                "text": "Hello   World"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "regex",
                            "value": "Hello\\\\s+World",
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
  @DisplayName("[REGEX] Should match pattern with word boundaries")
  void regexOperator_ShouldMatchWithWordBoundaries() throws Exception {
    String sourceJson = """
            {
                "text": "Hello World!"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "regex",
                            "value": ".*\\\\bWorld\\\\b.*",
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
  @DisplayName("[REGEX] Should match pattern with groups")
  void regexOperator_ShouldMatchWithGroups() throws Exception {
    String sourceJson = """
            {
                "text": "Name: John, Age: 30"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "regex",
                            "value": "Name:\\\\s*(\\\\w+),\\\\s*Age:\\\\s*(\\\\d+)",
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
  @DisplayName("[REGEX] Should not match partial pattern")
  void regexOperator_ShouldNotMatchPartialPattern() throws Exception {
    String sourceJson = """
            {
                "text": "Hello World and more text"
            }
            """;

    String mappingJson = """
            {
                "result": {
                    "type": "conditional",
                    "conditions": [
                        {
                            "path": "$.text",
                            "operator": "regex",
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
  @DisplayName("[REGEX] Should match email pattern")
  void regexOperator_ShouldMatchEmailPattern() throws Exception {
    String sourceJson =
        """
                {
                    "email": "test.user@example.com"
                }
                """;

    String mappingJson =
        """
                {
                    "result": {
                        "type": "conditional",
                        "conditions": [
                            {
                                "path": "$.email",
                                "operator": "regex",
                                "value": "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$",
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
  @DisplayName("[REGEX] Should match pattern with quantifiers")
  void regexOperator_ShouldMatchQuantifiers() throws Exception {
    String sourceJson =
        """
                {
                    "text": "aaaaabbbcc"
                }
                """;

    String mappingJson =
        """
                {
                    "result": {
                        "type": "conditional",
                        "conditions": [
                            {
                                "path": "$.text",
                                "operator": "regex",
                                "value": "a{5}b{3}c{2}",
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
  @DisplayName("[REGEX] Should match pattern with capture groups")
  void regexOperator_ShouldMatchCaptureGroups() throws Exception {
    String sourceJson =
        """
                {
                    "text": "John Doe <john.doe@example.com>"
                }
                """;

    String mappingJson =
        """
                {
                    "result": {
                        "type": "conditional",
                        "conditions": [
                            {
                                "path": "$.text",
                                "operator": "regex",
                                "value": "^([\\\\w\\\\s]+)\\\\s*<([\\\\w.]+@[\\\\w.]+)>$",
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
  @DisplayName("[REGEX] Should not match when pattern doesn't match")
  void regexOperator_ShouldNotMatchInvalidPattern() throws Exception {
    String sourceJson =
        """
                {
                    "email": "invalid-email"
                }
                """;

    String mappingJson =
        """
                {
                    "result": {
                        "type": "conditional",
                        "conditions": [
                            {
                                "path": "$.email",
                                "operator": "regex",
                                "value": "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$",
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
  @DisplayName("[REGEX] Should not match with case sensitivity")
  void regexOperator_ShouldHandleCaseSensitivity() throws Exception {
    String sourceJson =
        """
                {
                    "text": "HELLO WORLD"
                }
                """;

    String mappingJson =
        """
                {
                    "result": {
                        "type": "conditional",
                        "conditions": [
                            {
                                "path": "$.text",
                                "operator": "regex",
                                "value": "^hello world$",
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
  @DisplayName("[REGEX] Should handle null value in source")
  void regexOperator_ShouldHandleNullValue() throws Exception {
    String sourceJson =
        """
                {
                    "text": null
                }
                """;

    String mappingJson =
        """
                {
                    "result": {
                        "type": "conditional",
                        "conditions": [
                            {
                                "path": "$.text",
                                "operator": "regex",
                                "value": ".*",
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
  @DisplayName("[REGEX] Should handle empty source string")
  void regexOperator_ShouldHandleEmptySourceString() throws Exception {
    String sourceJson =
        """
                {
                    "text": ""
                }
                """;

    String mappingJson =
        """
                {
                    "result": {
                        "type": "conditional",
                        "conditions": [
                            {
                                "path": "$.text",
                                "operator": "regex",
                                "value": "^$",
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

  // Special Character Handling
  @Test
  @DisplayName("[REGEX] Should handle special regex characters")
  void regexOperator_ShouldHandleSpecialRegexCharacters() throws Exception {
    String sourceJson =
        """
                {
                    "text": "Hello [World] (2023) {test}"
                }
                """;

    String mappingJson =
        """
                {
                    "result": {
                        "type": "conditional",
                        "conditions": [
                            {
                                "path": "$.text",
                                "operator": "regex",
                                "value": "Hello\\\\s+\\\\[World\\\\]\\\\s+\\\\(\\\\d{4}\\\\)\\\\s+\\\\{test\\\\}",
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


  // Exception Handling Tests
  @Test
  @DisplayName("[REGEX] Should throw exception for invalid regex syntax")
  void regexOperator_ShouldThrowExceptionForInvalidRegex() {
    String sourceJson =
        """
                {
                    "text": "Hello World"
                }
                """;

    String mappingJson =
        """
                {
                    "result": {
                        "type": "conditional",
                        "conditions": [
                            {
                                "path": "$.text",
                                "operator": "regex",
                                "value": "[invalid regex(",
                                "result": "MATCH"
                            }
                        ],
                        "default": "NO_MATCH"
                    }
                }
                """;

    assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class)
        .hasCauseInstanceOf(java.util.regex.PatternSyntaxException.class);
  }

  @Test
  @DisplayName("[REGEX] Should throw exception for missing operator")
  void regexOperator_ShouldThrowExceptionForMissingOperator() {
    String sourceJson =
        """
                {
                    "text": "Hello World"
                }
                """;

    String mappingJson =
        """
                {
                    "result": {
                        "type": "conditional",
                        "conditions": [
                            {
                                "path": "$.text",
                                "value": ".*",
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
  @DisplayName("[REGEX] Should throw exception for missing path")
  void regexOperator_ShouldThrowExceptionForMissingPath() {
    String sourceJson =
        """
                {
                    "text": "Hello World"
                }
                """;

    String mappingJson =
        """
                {
                    "result": {
                        "type": "conditional",
                        "conditions": [
                            {
                                "operator": "regex",
                                "value": ".*",
                                "result": "MATCH"
                            }
                        ],
                        "default": "NO_MATCH"
                    }
                }
                """;

    assertThatThrownBy(() -> jsonMapper.transform(sourceJson, getCleanJson(mappingJson)))
        .isInstanceOf(JsonTransformationException.class);
        //.hasMessageContaining("Missing required field in rule: path");
  }

  @Test
  @DisplayName("[REGEX] Should throw exception for invalid JsonPath")
  void regexOperator_ShouldThrowExceptionForInvalidPath() {
    String sourceJson =
        """
                {
                    "text": "Hello World"
                }
                """;

    String mappingJson =
        """
                {
                    "result": {
                        "type": "conditional",
                        "conditions": [
                            {
                                "path": "$.[invalid",
                                "operator": "regex",
                                "value": ".*",
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
}
