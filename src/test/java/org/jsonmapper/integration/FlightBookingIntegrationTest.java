package org.jsonmapper.integration;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import org.jsonmapper.JsonMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Flight Booking Integration Test")
class FlightBookingIntegrationTest {

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

  @Test
  @DisplayName("Should correctly transform flight booking JSON")
  void shouldTransformFlightBooking() throws Exception {
    String sourceJson = loadResource("src/test/resources/flight-input.json");
    String mappingJson = loadResource("src/test/resources/flight-mapping.json");

    JsonNode result = jsonMapper.transform(sourceJson, getCleanJson(mappingJson));

    writeOutputToFile(result, "src/test/resources/transformed-flight.json");
    }

  private String loadResource(String path) throws Exception {
    return Files.readString(Paths.get(path));
  }

  private void writeOutputToFile(JsonNode jsonNode, String outputPath) throws Exception {
    java.nio.file.Files.createDirectories(java.nio.file.Paths.get("src/test/resources/output"));
    objectMapper.writerWithDefaultPrettyPrinter()
        .writeValue(new java.io.File(outputPath), jsonNode);
    System.out.println("Transformed output has been written to: " + outputPath);
  }
}
