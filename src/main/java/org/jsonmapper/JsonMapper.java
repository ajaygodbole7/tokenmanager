package org.jsonmapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.StreamSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** JsonMapper provides functionality to transform JSON documents based on mapping rules. */
public class JsonMapper {
  private static final Logger logger = LogManager.getLogger(JsonMapper.class);

  private final ObjectMapper objectMapper;
  private final Map<String, BiFunction<Object, Object[], Object>> builtInFunctions;
  private final Configuration jsonPathConfig;

  // Cache commonly used JsonPath expressions
  private final Map<String, JsonPath> pathCache;

  public JsonMapper() {
    logger.info("Initializing JsonMapper");
    try {
      this.objectMapper = new ObjectMapper();
      this.builtInFunctions = initializeBuiltInFunctions();
      this.jsonPathConfig =
          Configuration.builder()
              .options(Option.DEFAULT_PATH_LEAF_TO_NULL)
              .options(Option.SUPPRESS_EXCEPTIONS)
              .build();
      this.pathCache = new HashMap<>();
    } catch (Exception e) {
      logger.error("Failed to initialize JsonMapper", e);
      throw new JsonTransformationException("Failed to initialize JsonMapper", e);
    }
  }

  /** Transform JSON according to mapping rules */
  public JsonNode transform(String sourceJson, JsonNode mappingRules) {
    logger.debug("Starting JSON transformation");
    try {
      validateInputs(sourceJson, mappingRules);

      JsonNode sourceNode = parseSource(sourceJson);
      ObjectNode result = objectMapper.createObjectNode();

      processMappings(sourceNode, mappingRules, result);

      logger.debug("JSON transformation completed successfully");
      return result;
    } catch (JsonTransformationException e) {
      logger.error("JSON transformation failed", e);
      throw e;
    } catch (Exception e) {
      logger.error("Unexpected error during JSON transformation", e);
      throw new JsonTransformationException("Failed to transform JSON", e);
    }
  }

  /** Validate input JSON and mapping rules */
  private void validateInputs(String sourceJson, JsonNode mappingRules) {
    logger.debug("Validating inputs");

    if (sourceJson == null || sourceJson.trim().isEmpty()) {
      throw new JsonTransformationException("Source JSON cannot be null or empty");
    }

    if (mappingRules == null) {
      throw new JsonTransformationException("Mapping rules cannot be null");
    }

    if (!mappingRules.isObject()) {
      throw new JsonTransformationException("Mapping rules must be a JSON object");
    }

    try {
      objectMapper.readTree(sourceJson);
    } catch (Exception e) {
      throw new JsonTransformationException("Invalid source JSON format", e);
    }
  }

  /** Parse source JSON string to JsonNode */
  private JsonNode parseSource(String sourceJson) {
    try {
      return objectMapper.readTree(sourceJson);
    } catch (Exception e) {
      throw new JsonTransformationException("Failed to parse source JSON", e);
    }
  }

  /** Process all mappings from rules */
  private void processMappings(JsonNode source, JsonNode mappingRules, ObjectNode result) {
    mappingRules
        .fields()
        .forEachRemaining(
            mapping -> {
              String targetField = mapping.getKey();
              JsonNode rule = mapping.getValue();

              try {
                logger.debug("Processing mapping for field: {}", targetField);
                Object mappedValue = processRule(source, rule);
                addToResult(result, targetField, mappedValue);
              } catch (Exception e) {
                logger.error("Failed to process mapping for field: {}", targetField, e);
                throw new JsonTransformationException(
                    "Failed to process mapping for field: " + targetField, e);
              }
            });
  }

  /** Process individual mapping rule */
  private Object processRule(JsonNode source, JsonNode rule) {
    if (rule.isTextual()) {
      // Direct JsonPath mapping
      return evaluateJsonPath(source, rule.asText());
    }

    if (!rule.has("type")) {
      throw new JsonTransformationException("Mapping rule must have a 'type' field");
    }

    return switch (rule.get("type").asText()) {
      case "value" -> processValueMapping(source, rule);
      case "function" -> processFunctionMapping(source, rule);
      case "conditional" -> processConditionalMapping(source, rule);
      case "array" -> processArrayMapping(source, rule);
      default ->
          throw new JsonTransformationException(
              "Unknown mapping type: " + rule.get("type").asText());
    };
  }

  /** Process value mapping */
  /*
  private Object processValueMapping(JsonNode source, JsonNode rule) {
    logger.debug("Processing value mapping");
    validateRule(rule, "sourcePath");

    String sourcePath = rule.get("sourcePath").asText();
    Object sourceValue = evaluateJsonPath(source, sourcePath);

    if (sourceValue == null) {
      return rule.has("default") ? rule.get("default").asText() : null;
    }

    if (!rule.has("mappings")) {
      return sourceValue;
    }

    JsonNode mappings = rule.get("mappings");
    String sourceValueStr = String.valueOf(sourceValue);

    if (mappings.isArray()) {
      for (JsonNode mapping : mappings) {
        if (mapping.get("source").asText().equals(sourceValueStr)) {
          return mapping.get("target");
        }
      }
    } else if (mappings.isObject() && mappings.has(sourceValueStr)) {
      return mappings.get(sourceValueStr);
    }

    return rule.has("default") ? rule.get("default") : null;
  }

  private Object processValueMapping(JsonNode source, JsonNode rule) {
    logger.info("Processing value mapping");
    validateRule(rule, "sourcePath");

    String sourcePath = rule.get("sourcePath").asText();
    Object sourceValue = evaluateJsonPath(source, sourcePath);

    logger.info("Resolved source value: {}", sourceValue);

    if (sourceValue == null) {
      logger.info("Source value is null. Using default if available.");
      return rule.has("default") ? rule.get("default").asText() : null;
    }

    // Convert the mappings to a Map for quick lookup
    Map<String, String> mappingsMap = new HashMap<>();
    if (rule.has("mappings") && rule.get("mappings").isArray()) {
      for (JsonNode mapping : rule.get("mappings")) {
        mappingsMap.put(mapping.get("source").asText(), mapping.get("target").asText());
      }
    }

    String sourceValueStr = unquoteString(String.valueOf(sourceValue));
    logger.info("Source value as string: {}", sourceValueStr);

    // Check for a match in the mappings
    if (mappingsMap.containsKey(sourceValueStr)) {
      logger.info("Match found for sourceValue: {}, target: {}", sourceValueStr, mappingsMap.get(sourceValueStr));
      return mappingsMap.get(sourceValueStr);
    }

    logger.info("No match found. Using default value.");
    return rule.has("default") ? rule.get("default").asText() : null;
  }

*/
  private Object processValueMapping(JsonNode source, JsonNode rule) {
    logger.info("Processing value mapping");
    validateRule(rule, "sourcePath");

    String sourcePath = rule.get("sourcePath").asText();
    Object sourceValue = evaluateJsonPath(source, sourcePath);

    logger.info("Resolved source value: {}", sourceValue);

    if (sourceValue == null) {
      logger.info("Source value is null. Using default if available.");
      return rule.has("default") ? rule.get("default").asText() : null;
    }

    // Validate mappings
    if (!rule.has("mappings") || !rule.get("mappings").isArray()) {
      throw new JsonTransformationException("Missing or invalid 'mappings' in rule for sourcePath: " + sourcePath);
    }

    // Convert mappings to a Map for quick lookup
    Map<String, String> mappingsMap = new HashMap<>();
    for (JsonNode mapping : rule.get("mappings")) {
      mappingsMap.put(mapping.get("source").asText(), mapping.get("target").asText());
    }

    // Check if sourceValue is an array
    if (sourceValue instanceof JsonNode && ((JsonNode) sourceValue).isArray()) {
      logger.info("Source value is an array. Processing each element.");
      ArrayNode sourceArray = (ArrayNode) sourceValue;
      ArrayNode mappedArray = sourceArray.arrayNode();
      for (JsonNode element : sourceArray) {
        String elementStr = element.asText();
        String mappedValue = mappingsMap.getOrDefault(elementStr, rule.has("default") ? rule.get("default").asText() : elementStr);
        mappedArray.add(mappedValue);
      }
      return mappedArray;
    }

    // Handle single values
    String sourceValueStr = unquoteString(String.valueOf(sourceValue));
    logger.info("Source value as string: {}", sourceValueStr);

    // Check for a match in the mappings
    if (mappingsMap.containsKey(sourceValueStr)) {
      logger.info("Match found for sourceValue: {}, target: {}", sourceValueStr, mappingsMap.get(sourceValueStr));
      return mappingsMap.get(sourceValueStr);
    }

    logger.info("No match found. Using default value.");
    return rule.has("default") ? rule.get("default").asText() : null;
  }



  /** Utility to strip quotes from a string */
  private String unquoteString(String value) {
    if (value != null && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }



  /** Process function mapping */
  private Object processFunctionMapping(JsonNode source, JsonNode rule) {
    logger.debug("Processing function mapping");
    validateRule(rule, "function");

    String functionName = rule.get("function").asText();
    BiFunction<Object, Object[], Object> function = builtInFunctions.get(functionName);

    if (function == null) {
      throw new JsonTransformationException("Unknown function: " + functionName);
    }

    Object input = source;
    if (rule.has("sourcePath")) {
      input = evaluateJsonPath(source, rule.get("sourcePath").asText());
    }

    Object[] args = extractFunctionArgs(rule);

    try {
      return function.apply(input, args);
    } catch (Exception e) {
      throw new JsonTransformationException("Function execution failed: " + functionName, e);
    }
  }

  /** Process conditional mapping */
  /*
  private Object processConditionalMapping(JsonNode source, JsonNode rule) {
    logger.debug("Processing conditional mapping");
    validateRule(rule, "conditions");

    JsonNode conditions = rule.get("conditions");
    if (!conditions.isArray()) {
      throw new JsonTransformationException("Conditions must be an array");
    }

    for (JsonNode condition : conditions) {
      if (evaluateCondition(source, condition)) {
        return processRule(source, condition.get("result"));
      }
    }

    return rule.has("default") ? processRule(source, rule.get("default")) : null;
  }

   */

  private Object processConditionalMapping(JsonNode source, JsonNode rule) {
    logger.debug("Processing conditional mapping");
    validateRule(rule, "conditions");

    JsonNode conditions = rule.get("conditions");
    if (!conditions.isArray()) {
      throw new JsonTransformationException("Conditions must be an array");
    }

    for (JsonNode condition : conditions) {
      validateRule(condition, "path", "operator", "value", "result");

      if (evaluateCondition(source, condition)) {
        JsonNode result = condition.get("result");
        // Handle both simple values and complex mappings
        if (result.isValueNode()) {
          return result.asText();
        } else if (result.isObject() && result.has("type")) {
          return processRule(source, result);
        } else {
          return result;
        }
      }
    }

    // Handle default value same way as condition results
    if (rule.has("default")) {
      JsonNode defaultValue = rule.get("default");
      if (defaultValue.isValueNode()) {
        return defaultValue.asText();
      } else if (defaultValue.isObject() && defaultValue.has("type")) {
        return processRule(source, defaultValue);
      } else {
        return defaultValue;
      }
    }

    return null;
  }

  /** Process array mapping */
  /*
  private Object processArrayMapping(JsonNode source, JsonNode rule) {
    logger.debug("Processing array mapping");
    validateRule(rule, "sourcePath");

    String sourcePath = rule.get("sourcePath").asText();
    Object sourceValue = evaluateJsonPath(source, sourcePath);

    if (!(sourceValue instanceof List || sourceValue instanceof ArrayNode)) {
      throw new JsonTransformationException("Array mapping source must resolve to an array");
    }

    ArrayNode result = objectMapper.createArrayNode();
    JsonNode itemMapping = rule.get("itemMapping");

    if (sourceValue instanceof List<?> list) {
      for (Object item : list) {
        Object transformedItem = processRule(objectMapper.valueToTree(item), itemMapping);
        addToArray(result, transformedItem);
      }
    } else if (sourceValue instanceof ArrayNode array) {
      for (JsonNode item : array) {
        Object transformedItem = processRule(item, itemMapping);
        addToArray(result, transformedItem);
      }
    }

    return result;
  }
  */

  private void validateArrayMapping(JsonNode rule) {
    validateRule(rule, "sourcePath", "itemMapping");
    JsonNode itemMapping = rule.get("itemMapping");
    if (!itemMapping.isObject()) {
      throw new JsonTransformationException("Invalid itemMapping format - must be an object");
    }
  }

  private JsonNode ensureJsonNode(Object item) {
    if (item == null) {
      return objectMapper.createObjectNode();
    }
    return (item instanceof JsonNode) ? (JsonNode) item : objectMapper.valueToTree(item);
  }

  private void logMappingError(String sourcePath, JsonNode itemMapping, Exception e) {
    logger.error("Failed to process array mapping for path: {} with error: {}", sourcePath, e.getMessage());
    throw new JsonTransformationException(
        String.format("Error processing array with sourcePath: %s and itemMapping: %s",
                      sourcePath, itemMapping), e);
  }

  private Object processArrayMapping(JsonNode source, JsonNode rule) {
    logger.debug("Processing array mapping");
    validateArrayMapping(rule);

    String sourcePath = rule.get("sourcePath").asText();
    Object sourceValue = evaluateJsonPath(source, sourcePath);

    if (!(sourceValue instanceof List || sourceValue instanceof ArrayNode)) {
      logger.warn("Source path resolved to a non-array: {}. Using fallback value.", sourcePath);
      return objectMapper.createArrayNode();
    }

    ArrayNode result = objectMapper.createArrayNode();
    JsonNode itemMapping = rule.get("itemMapping");

    try {
      if (sourceValue instanceof List<?> list) {
        list.stream()
            .map(this::ensureJsonNode)
            .map(item -> createMappedItem(item, itemMapping))
            .forEach(result::add);
      } else if (sourceValue instanceof ArrayNode array) {
        array.forEach(item -> result.add(createMappedItem(item, itemMapping)));
      }
    } catch (Exception e) {
      logMappingError(sourcePath, itemMapping, e);
    }

    return result;
  }

  private JsonNode createMappedItem(JsonNode sourceItem, JsonNode itemMapping) {
    logger.debug("Mapping array item: {}", sourceItem);
    ObjectNode mappedItem = objectMapper.createObjectNode();

    itemMapping.fields().forEachRemaining(field -> {
      String targetField = field.getKey();
      JsonNode mappingRule = field.getValue();

      try {
        Object mappedValue;
        // Direct JsonPath mapping using the field directly
        if (mappingRule.isTextual()) {
          mappedValue = evaluateJsonPath(sourceItem, mappingRule.asText());
        } else {
          // Complex mapping (value/function/conditional)
          mappedValue = processRule(sourceItem, mappingRule);
        }

        if (mappedValue != null) {
          addToResult(mappedItem, targetField, mappedValue);
        }
      } catch (Exception e) {
        logger.error("Failed to map field: {}. Error: {}", targetField, e.getMessage());
        throw new JsonTransformationException("Failed to process field: " + targetField, e);
      }
    });

    return mappedItem;
  }

  private Object evaluateJsonPath(JsonNode source, String path) {
    if (source == null || path == null || path.isBlank()) {
      throw new IllegalArgumentException("Source JSON and path must not be null or empty.");
    }
    try {
      JsonPath compiledPath = pathCache.computeIfAbsent(path, p -> JsonPath.compile(p));
      Object result = compiledPath.read(source.toString());

      // Check if the result is a JSON object, array, or primitive
      if (result instanceof Map || result instanceof List || result instanceof String) {
        return objectMapper.valueToTree(result); // Convert to JsonNode
      }

      return result;

    } catch (PathNotFoundException e) {
      logger.debug("Path not found: {}", path);
      return null;
    } catch (InvalidPathException e) {
      throw new JsonTransformationException("Invalid JsonPath: " + path, e); // Pass InvalidPathException as cause
    } catch (Exception e) {
      throw new JsonTransformationException("Failed to evaluate JsonPath: " + path, e);
    }
  }

  /** Evaluate condition */
  private boolean evaluateCondition(JsonNode source, JsonNode condition) {
    validateRule(condition, "path", "operator", "value");

    String path = condition.get("path").asText();
    String operator = condition.get("operator").asText();
    JsonNode compareValue = condition.get("value");

    Object sourceValue = evaluateJsonPath(source, path);
    //if (sourceValue == null) return false;

    // For null comparisons with eq/ne, we want to proceed
    // For other operators, sourceValue must be non-null
    if (sourceValue == null) {
      if (!"eq".equals(operator) && !"equals".equals(operator) &&
          !"ne".equals(operator) && !"notEquals".equals(operator)) {
        return false;
      }
    }

    return switch (operator) {
      case "eq", "equals" -> compareValues(sourceValue, compareValue, true);
      case "ne", "notEquals" -> compareValues(sourceValue, compareValue, false);
      case "gt" -> compareNumbers(sourceValue, compareValue) > 0;
      case "lt" -> compareNumbers(sourceValue, compareValue) < 0;
      case "gte" -> compareNumbers(sourceValue, compareValue) >= 0;
      case "lte" -> compareNumbers(sourceValue, compareValue) <= 0;
      case "contains" -> sourceValue.toString().contains(compareValue.asText());
      case "startsWith" -> unquoteString(sourceValue.toString()).startsWith(compareValue.asText());
      case "endsWith" -> unquoteString(sourceValue.toString()).endsWith(compareValue.asText());
      case "regex" -> unquoteString(sourceValue.toString()).matches(compareValue.asText());
      default -> throw new JsonTransformationException("Unknown operator: " + operator);
    };
  }

  /** Extract function arguments */
  private Object[] extractFunctionArgs(JsonNode rule) {
    if (!rule.has("args")) {
      return new Object[0];
    }

    JsonNode argsNode = rule.get("args");
    if (!argsNode.isArray()) {
      throw new JsonTransformationException("Function args must be an array");
    }

    Object[] args = new Object[argsNode.size()];
    for (int i = 0; i < argsNode.size(); i++) {
      JsonNode arg = argsNode.get(i);
      if (arg.isNumber()) {
        args[i] = arg.numberValue();
      } else if (arg.isBoolean()) {
        args[i] = arg.booleanValue();
      } else {
        args[i] = arg.asText();
      }
    }
    return args;
  }

  private void addToResult(ObjectNode result, String field, Object value) {
    if (value == null) {
      result.putNull(field);
    } else if (value instanceof JsonNode) {
      result.set(field, (JsonNode) value);
    } else if (value instanceof String) {
      result.put(field, (String) value);
    } else if (value instanceof Number number) {
      // Handle different numeric types
      if (number instanceof Integer) {
        result.put(field, number.intValue());
      } else if (number instanceof Long) {
        result.put(field, number.longValue());
      } else if (number instanceof Double) {
        result.put(field, number.doubleValue());
      } else if (number instanceof Float) {
        result.put(field, number.floatValue());
      } else if (number instanceof BigDecimal) {
        result.put(field, (BigDecimal) number);
      } else {
        // Default to double for other numeric types
        result.put(field, number.doubleValue());
      }
    } else if (value instanceof Boolean) {
      result.put(field, (Boolean) value);
    } else if (value instanceof Collection || value.getClass().isArray()) {
      result.set(field, objectMapper.valueToTree(value));
    } else {
      result.put(field, value.toString());
    }
  }

  /**
   * Add value to array
   */
  private void addToArray(ArrayNode array, Object value) {
    if (value == null) {
      array.addNull();
    } else if (value instanceof JsonNode) {
      array.add((JsonNode) value);
    } else if (value instanceof String) {
      array.add((String) value);
    } else if (value instanceof Number number) {
      // Handle different numeric types
      if (number instanceof Integer) {
        array.add(number.intValue());
      } else if (number instanceof Long) {
        array.add(number.longValue());
      } else if (number instanceof Double) {
        array.add(number.doubleValue());
      } else if (number instanceof Float) {
        array.add(number.floatValue());
      } else if (number instanceof BigDecimal) {
        array.add((BigDecimal) number);
      } else {
        // Default to double for other numeric types
        array.add(number.doubleValue());
      }
    } else if (value instanceof Boolean) {
      array.add((Boolean) value);
    } else if (value instanceof Collection || value.getClass().isArray()) {
      array.add(objectMapper.valueToTree(value));
    } else {
      array.add(value.toString());
    }
  }
  /** Validate rule contains required fields */
  private void validateRule(JsonNode rule, String... requiredFields) {
    for (String field : requiredFields) {
      if (!rule.has(field)) {
        throw new JsonTransformationException("Missing required field in rule: " + field);
      }
    }
  }

  /** Compare values for equality */
  /*
  private boolean compareValues(Object source, JsonNode target, boolean equals) {
    if (source instanceof Number && target.isNumber()) {
      return equals == (compareNumbers(source, target) == 0);
    }

    return equals == String.valueOf(source).equals(target.asText());
  }
   */
  /**
   * Compares two values based on the desired comparison operator (eq/equals or ne/notEquals)
   * @param source The source value from JsonPath evaluation
   * @param target The target value from the mapping rule
   * @param isEqualityCheck true if checking for equality (eq/equals), false if checking for inequality (ne/notEquals)
   * @return Returns true if the values match the desired comparison
   */
  private boolean compareValues(Object source, JsonNode target, boolean isEqualityCheck) {
    // Handle null values - simplest case first
    if (target.isNull()) {
      return isEqualityCheck ? (source == null) : (source != null);
    }

    if (source == null) {
      // We know target is not null here because of previous check
      return !isEqualityCheck;
    }

    // Handle integers - both sides must be integers
    if (source instanceof Integer && target.isInt()) {
      return isEqualityCheck == (((Integer) source).intValue() == target.asInt());
    }

    // Handle decimals/doubles - both sides must be doubles
    if (source instanceof Double && target.isDouble()) {
      return isEqualityCheck == (Double.compare(((Double) source).doubleValue(), target.asDouble()) == 0);
    }

    // Handle strings - ensure both sides are treated as strings
    if (target.isTextual()) {
      String sourceStr = unquoteString(String.valueOf(source));
      String targetStr = target.asText();
      return isEqualityCheck == sourceStr.equals(targetStr);
    }

    // Handle booleans - both sides must be booleans
    if (source instanceof Boolean && target.isBoolean()) {
      return isEqualityCheck == ((Boolean) source).equals(target.booleanValue());
    }

    // If types don't match or no specific type handling
    return !isEqualityCheck;
  }


  /** Compare numeric values */
  private int compareNumbers(Object source, JsonNode target) {
    BigDecimal sourceNum = new BigDecimal(String.valueOf(source));
    BigDecimal targetNum = new BigDecimal(target.asText());
    return sourceNum.compareTo(targetNum);
  }

  private Map<String, BiFunction<Object, Object[], Object>> initializeBuiltInFunctions() {
    Map<String, BiFunction<Object, Object[], Object>> functions = new HashMap<>();

    // String Functions
    //functions.put("$string", (ctx, args) -> unquoteString(String.valueOf(ctx)));
    functions.put("$string", (ctx, args) -> {
      if (ctx == null) {
        return "";  // Return empty string for null input
      }
      return unquoteString(String.valueOf(ctx));
    });
    functions.put("$uppercase", (ctx, args) -> unquoteString(String.valueOf(ctx)).toUpperCase());
    functions.put("$lowercase", (ctx, args) -> unquoteString(String.valueOf(ctx)).toLowerCase());
    functions.put("$trim", (ctx, args) -> unquoteString(String.valueOf(ctx)).trim());
    functions.put("$substring", (ctx, args) -> {
      String str = unquoteString(String.valueOf(ctx));
      int start = args.length > 0 ? ((Number) args[0]).intValue() : 0;
      int end = args.length > 1 ? ((Number) args[1]).intValue() : str.length();
      return str.substring(Math.min(start, str.length()), Math.min(end, str.length()));
    });

    // Numeric Functions
   // functions.put("$number", (ctx, args) -> new BigDecimal(unquoteString(String.valueOf(ctx))));

    // Numeric Functions
    functions.put("$number", (ctx, args) -> {
      if (ctx == null) {
        return null;  // Return null for null input
      }
      try {
        return new BigDecimal(unquoteString(String.valueOf(ctx)));
      } catch (NumberFormatException e) {
        return null;  // Return null for invalid number format
      }
    });

    functions.put("$round", (ctx, args) -> {
      BigDecimal number = new BigDecimal(unquoteString(String.valueOf(ctx)));
      int scale = args.length > 0 ? ((Number) args[0]).intValue() : 0;
      return number.setScale(scale, RoundingMode.HALF_UP);
    });
    functions.put("$sum", (ctx, args) -> calculateSum(ctx));
    /*
    functions.put("$sum", (ctx, args) -> {
      if (ctx instanceof Collection<?> list) {
        return list.stream()
            .filter(item -> item instanceof Number)
            .map(item -> new BigDecimal(unquoteString(String.valueOf(item))))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
      }
      return BigDecimal.ZERO;
    });

     */

    // Date Functions
    functions.put("$now", (ctx, args) -> LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
    functions.put("$formatDate", (ctx, args) -> {
      String format = args.length > 0 ? String.valueOf(args[0]) : "yyyy-MM-dd HH:mm:ss";
      return LocalDateTime.parse(unquoteString(String.valueOf(ctx))).format(DateTimeFormatter.ofPattern(format));
    });

    // Utility Functions
    functions.put("$uuid", (ctx, args) -> UUID.randomUUID().toString());
    functions.put("$concat", (ctx, args) -> {
      StringBuilder result = new StringBuilder(unquoteString(String.valueOf(ctx)));
      for (Object arg : args) {
        result.append(arg);
      }
      return result.toString();
    });

    return functions;
  }

  private BigDecimal calculateSum(Object input) {
    // Handle Collection type (e.g., List)
    if (input instanceof Collection<?> list) {
      return list.stream()
          .filter(item -> item instanceof Number)
          .map(item -> new BigDecimal(unquoteString(String.valueOf(item))))
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Handle JsonNode array
    if (input instanceof ArrayNode arrayNode) {
      return StreamSupport.stream(arrayNode.spliterator(), false)
          .filter(JsonNode::isNumber)
          .map(JsonNode::decimalValue)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Return zero for unsupported types
    return BigDecimal.ZERO;
  }
}
