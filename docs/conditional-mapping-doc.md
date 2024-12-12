# Conditional Mapping Feature

## Overview

Conditional mapping enables dynamic transformation of JSON data based on conditions. It allows you to apply different mappings depending on the values in your source JSON, making it possible to implement complex business rules and data transformation logic.

## Key Features

- **Multiple Comparison Operators**: Support for various comparison operations (equals, greater than, less than, etc.)
- **String Operations**: String-specific operations like contains, startsWith, endsWith, and regex matching
- **Type-Safe Comparisons**: Proper handling of different data types (string, number, boolean)
- **Default Values**: Fallback values when conditions aren't met
- **Null Handling**: Graceful handling of null and missing values

## Basic Structure

A conditional mapping has the following structure:

```json
{
    "targetField": {
        "type": "conditional",
        "conditions": [
            {
                "path": "$.sourcePath",
                "operator": "operatorType",
                "value": comparisonValue,
                "result": "resultValue"
            }
        ],
        "default": "defaultValue"
    }
}
```

## Supported Operators

### Equality Operators

#### 1. Equals (eq, equals)
Tests for exact equality between values.

**Input JSON:**
```json
{
    "status": "active"
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "conditional",
        "conditions": [
            {
                "path": "$.status",
                "operator": "eq",
                "value": "active",
                "result": "STATUS_ACTIVE"
            }
        ],
        "default": "STATUS_OTHER"
    }
}
```

**Output JSON:**
```json
{
    "result": "STATUS_ACTIVE"
}
```

#### 2. Not Equals (ne, notEquals)
Tests for inequality between values.

**Input JSON:**
```json
{
    "status": "inactive"
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "conditional",
        "conditions": [
            {
                "path": "$.status",
                "operator": "ne",
                "value": "active",
                "result": "STATUS_NOT_ACTIVE"
            }
        ],
        "default": "STATUS_ACTIVE"
    }
}
```

**Output JSON:**
```json
{
    "result": "STATUS_NOT_ACTIVE"
}
```

### Numeric Comparison Operators

#### 1. Greater Than (gt)
Tests if source value is greater than comparison value.

**Input JSON:**
```json
{
    "amount": 1500
}
```

**Mapping Rules:**
```json
{
    "tier": {
        "type": "conditional",
        "conditions": [
            {
                "path": "$.amount",
                "operator": "gt",
                "value": 1000,
                "result": "HIGH_TIER"
            }
        ],
        "default": "LOW_TIER"
    }
}
```

**Output JSON:**
```json
{
    "tier": "HIGH_TIER"
}
```

#### 2. Less Than (lt)
Tests if source value is less than comparison value.

**Input JSON:**
```json
{
    "temperature": 35.5
}
```

**Mapping Rules:**
```json
{
    "status": {
        "type": "conditional",
        "conditions": [
            {
                "path": "$.temperature",
                "operator": "lt",
                "value": 37.0,
                "result": "NORMAL"
            }
        ],
        "default": "FEVER"
    }
}
```

**Output JSON:**
```json
{
    "status": "NORMAL"
}
```

#### 3. Greater Than or Equal To (gte)
Tests if source value is greater than or equal to comparison value.

**Input JSON:**
```json
{
    "amount": 1000
}
```

**Mapping Rules:**
```json
{
    "tier": {
        "type": "conditional",
        "conditions": [
            {
                "path": "$.amount",
                "operator": "gte",
                "value": 1000,
                "result": "HIGH_TIER"
            }
        ],
        "default": "LOW_TIER"
    }
}
```

**Output JSON:**
```json
{
    "tier": "HIGH_TIER"
}
```

#### 4. Less Than or Equal To (lte)
Tests if source value is less than or equal to comparison value.

**Input JSON:**
```json
{
    "stock": 10
}
```

**Mapping Rules:**
```json
{
    "status": {
        "type": "conditional",
        "conditions": [
            {
                "path": "$.stock",
                "operator": "lte",
                "value": 10,
                "result": "LOW_STOCK"
            }
        ],
        "default": "NORMAL_STOCK"
    }
}
```

**Output JSON:**
```json
{
    "status": "LOW_STOCK"
}
```

### String Operators

#### 1. Contains
Tests if source string contains the comparison value.

**Input JSON:**
```json
{
    "description": "This is a test message"
}
```

**Mapping Rules:**
```json
{
    "hasTest": {
        "type": "conditional",
        "conditions": [
            {
                "path": "$.description",
                "operator": "contains",
                "value": "test",
                "result": true
            }
        ],
        "default": false
    }
}
```

**Output JSON:**
```json
{
    "hasTest": true
}
```

#### 2. Starts With
Tests if source string starts with the comparison value.

**Input JSON:**
```json
{
    "code": "PRD-12345"
}
```

**Mapping Rules:**
```json
{
    "type": {
        "type": "conditional",
        "conditions": [
            {
                "path": "$.code",
                "operator": "startsWith",
                "value": "PRD-",
                "result": "PRODUCT"
            }
        ],
        "default": "OTHER"
    }
}
```

**Output JSON:**
```json
{
    "type": "PRODUCT"
}
```

#### 3. Ends With
Tests if source string ends with the comparison value.

**Input JSON:**
```json
{
    "filename": "document.pdf"
}
```

**Mapping Rules:**
```json
{
    "fileType": {
        "type": "conditional",
        "conditions": [
            {
                "path": "$.filename",
                "operator": "endsWith",
                "value": ".pdf",
                "result": "PDF_DOCUMENT"
            }
        ],
        "default": "UNKNOWN"
    }
}
```

**Output JSON:**
```json
{
    "fileType": "PDF_DOCUMENT"
}
```

#### 4. Regex
Tests if source string matches the regular expression pattern.

**Input JSON:**
```json
{
    "email": "user@example.com"
}
```

**Mapping Rules:**
```json
{
    "isValidEmail": {
        "type": "conditional",
        "conditions": [
            {
                "path": "$.email",
                "operator": "regex",
                "value": "^[A-Za-z0-9+_.-]+@(.+)$",
                "result": true
            }
        ],
        "default": false
    }
}
```

**Output JSON:**
```json
{
    "isValidEmail": true
}
```

## Error Handling

**Invalid Paths**: JsonTransformationException is thrown for invalid JSONPath expressions.

**Type Mismatches**: Returns default value when comparing incompatible types.

**Null Values**: Gracefully handles null values by returning the default result.

**Missing Fields**: Returns default value for non-existent fields.
