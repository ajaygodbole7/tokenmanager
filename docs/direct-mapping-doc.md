# Direct Mapping Feature

## Overview

Direct mapping is the simplest and most straightforward way to transform JSON data using the JSON Integration Mapper. It allows you to map fields from a source JSON document to a target JSON document using either JSONPath expressions or literal values.

## Key Features

- **Simple Field Mappings**: Map source fields to target fields using JSONPath syntax
- **Literal Value Support**: Assign string and boolean values directly without JSONPath
- **Primitive Type Support**: Handles string, number, boolean, and null values
- **Array Handling**: Support for array indexing and slicing
- **Nested Object Support**: Access deeply nested fields using dot notation
- **Special Character Support**: Handle fields with special characters and Unicode

## Basic Usage

The mapper supports two types of direct mapping:

1. **JSONPath Mapping**: Map fields using JSONPath expressions
```json
{
    "targetField": "$.sourceField"
}
```

2. **Literal Value Assignment**: Assign values directly without JsonPath evaluation
```json
{
    "targetField": "literal string value",
    "boolField": true
}
```

## Examples

### 1. Mapping Primitive Types

**Input JSON:**
```json
{
    "stringField": "test",
    "intField": 42,
    "doubleField": 42.42,
    "booleanField": true,
    "nullField": null
}
```

**Mapping Rules:**
```json
{
    "text": "$.stringField",
    "number": "$.intField",
    "decimal": "$.doubleField",
    "flag": "$.booleanField",
    "empty": "$.nullField"
}
```

**Output JSON:**
```json
{
    "text": "test",
    "number": 42,
    "decimal": 42.42,
    "flag": true,
    "empty": null
}
```

### 2. Direct Value Assignment

**Input JSON:**
```json
{
    "someField": "value"
}
```

**Mapping Rules:**
```json
{
    "directString": "literal string value",
    "jsonPath": "$.someField",
    "booleanValue": true
}
```

**Output JSON:**
```json
{
    "directString": "literal string value",
    "jsonPath": "value",
    "booleanValue": true
}
```

### 3. Handling Special Characters

**Input JSON:**
```json
{
    "special": "!@#$%^&*()_+",
    "unicode": "Hello 世界",
    "whitespace": "   spaces   ",
    "newlines": "line1\nline2"
}
```

**Mapping Rules:**
```json
{
    "specialChars": "$.special",
    "unicodeText": "$.unicode",
    "spacedText": "$.whitespace",
    "multiline": "$.newlines"
}
```

**Output JSON:**
```json
{
    "specialChars": "!@#$%^&*()_+",
    "unicodeText": "Hello 世界",
    "spacedText": "   spaces   ",
    "multiline": "line1\nline2"
}
```

### 4. Nested Field Access

**Input JSON:**
```json
{
    "level1": {
        "level2": {
            "level3": {
                "value": "nested value"
            }
        }
    }
}
```

**Mapping Rules:**
```json
{
    "deepValue": "$.level1.level2.level3.value"
}
```

**Output JSON:**
```json
{
    "deepValue": "nested value"
}
```

### 5. Array Operations

#### Array Indexing

**Input JSON:**
```json
{
    "array": ["first", "second", "third"]
}
```

**Mapping Rules:**
```json
{
    "first": "$.array[0]",
    "second": "$.array[1]",
    "last": "$.array[2]",
    "nonExistent": "$.array[3]"
}
```

**Output JSON:**
```json
{
    "first": "first",
    "second": "second",
    "last": "third",
    "nonExistent": null
}
```

#### Array Slicing

**Input JSON:**
```json
{
    "numbers": [1, 2, 3, 4, 5]
}
```

**Mapping Rules:**
```json
{
    "firstTwo": "$.numbers[0:2]",
    "lastTwo": "$.numbers[-2:]",
    "middle": "$.numbers[1:4]"
}
```

**Output JSON:**
```json
{
    "firstTwo": [1, 2],
    "lastTwo": [4, 5],
    "middle": [2, 3, 4]
}
```

### 6. Different JSONPath Notations

**Input JSON:**
```json
{
    "store": {
        "book": [
            {"title": "Book1", "price": 10},
            {"title": "Book2", "price": 20}
        ]
    }
}
```

**Mapping Rules:**
```json
{
    "bracket": "$['store']['book'][0]['title']",
    "dot": "$.store.book[0].title",
    "filter": "$.store.book[?(@.price > 15)].title",
    "wildcard": "$.store.book[*].title"
}
```

**Output JSON:**
```json
{
    "bracket": "Book1",
    "dot": "Book1",
    "filter": ["Book2"],
    "wildcard": ["Book1", "Book2"]
}
```

## Error Handling

**Invalid JSONPath**: Throws JsonTransformationException with InvalidPathException as root cause when JSONPath syntax is incorrect.

**Missing Fields**: Returns null for non-existent fields while continuing to process other mappings.

**Type Mismatches**: Preserves original data types when possible and handles type coercion when needed.
