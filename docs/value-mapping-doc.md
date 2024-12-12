# Value Mapping Feature

## Overview

Value mapping allows you to transform field values from one set of values to another using explicit mapping rules. This feature is particularly useful when you need to standardize or normalize values across different systems or perform data categorization.

## Key Features

- **Explicit Value Mappings**: Define source-to-target value transformations
- **Default Values**: Handle missing or unmapped values
- **Array Support**: Map multiple values in array fields
- **Nested Field Access**: Access and map deeply nested JSON values
- **Null Handling**: Graceful handling of null and missing values

## Basic Structure

A value mapping has the following structure:

```json
{
    "targetField": {
        "type": "value",
        "sourcePath": "$.path.to.field",
        "mappings": [
            { "source": "sourceValue1", "target": "targetValue1" },
            { "source": "sourceValue2", "target": "targetValue2" }
        ],
        "default": "defaultValue"
    }
}
```

## Examples

### 1. Basic Value Mapping

Maps values based on explicit mappings with a default value.

**Input JSON:**
```json
{
    "category": "premium"
}
```

**Mapping Rules:**
```json
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
```

**Output JSON:**
```json
{
    "mappedCategory": "gold"
}
```

### 2. Handling Missing Values

Returns default value when source field is missing.

**Input JSON:**
```json
{
    "type": "basic"
}
```

**Mapping Rules:**
```json
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
```

**Output JSON:**
```json
{
    "mappedCategory": "bronze"
}
```

### 3. Mapping Array Values

Maps multiple values in an array field.

**Input JSON:**
```json
{
    "categories": ["premium", "basic", "unknown"]
}
```

**Mapping Rules:**
```json
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
```

**Output JSON:**
```json
{
    "mappedCategories": ["gold", "silver", "bronze"]
}
```

### 4. Nested Field Mapping

Maps values from deeply nested fields.

**Input JSON:**
```json
{
    "data": {
        "details": {
            "category": "basic"
        }
    }
}
```

**Mapping Rules:**
```json
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
```

**Output JSON:**
```json
{
    "mappedCategory": "silver"
}
```

### 5. Complex Hierarchical Mapping

Maps values within array elements in a complex structure.

**Input JSON:**
```json
{
    "cart": {
        "items": [
            { "id": 1, "name": "Laptop", "category": "electronics" },
            { "id": 2, "name": "Shirt", "category": "apparel" },
            { "id": 3, "name": "Apple", "category": "groceries" }
        ]
    }
}
```

**Mapping Rules:**
```json
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
```

**Output JSON:**
```json
{
    "cart": [
        {
            "id": 1,
            "name": "Laptop",
            "category": "Electronics & Gadgets"
        },
        {
            "id": 2,
            "name": "Shirt",
            "category": "Clothing"
        },
        {
            "id": 3,
            "name": "Apple",
            "category": "Fruits & Vegetables"
        }
    ]
}
```

## Error Handling

**Invalid JsonPath**: Throws JsonTransformationException when the JsonPath expression is invalid.

**Missing Mappings**: Throws JsonTransformationException when required mapping configuration is missing.

**Null Values**: Returns default value for null source values.

**Missing Fields**: Returns default value for non-existent source fields.
