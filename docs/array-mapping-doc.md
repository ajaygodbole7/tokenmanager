# Array Mapping Feature

## Overview

Array mapping provides powerful capabilities for transforming arrays of data in JSON documents. It supports simple array operations, array indexing, slicing, filtering, and complex nested array transformations.

## Key Features

- **Simple Array Mapping**: Direct mapping of arrays
- **Array Indexing**: Access elements by position
- **Array Slicing**: Extract portions of arrays
- **Array Filtering**: Filter elements based on conditions
- **Nested Arrays**: Handle multi-level array structures
- **Complex Transformations**: Combine with other mapping types
- **Single Object Wrapping**: Convert single objects to arrays

## Basic Array Operations

### 1. Simple Array Mapping

Maps an array directly from source to target.

**Input JSON:**
```json
{
    "numbers": [1, 2, 3, 4, 5]
}
```

**Mapping Rules:**
```json
{
    "result": "$.numbers"
}
```

**Output JSON:**
```json
{
    "result": [1, 2, 3, 4, 5]
}
```

### 2. Array Indexing

Access specific array elements using index.

**Input JSON:**
```json
{
    "array": [10, 20, 30, 40, 50]
}
```

**Mapping Rules:**
```json
{
    "first": "$.array[0]",
    "third": "$.array[2]",
    "last": "$.array[-1]"
}
```

**Output JSON:**
```json
{
    "first": 10,
    "third": 30,
    "last": 50
}
```

### 3. Array Slicing

Extract portions of arrays using slice notation.

**Input JSON:**
```json
{
    "array": [1, 2, 3, 4, 5]
}
```

**Mapping Rules:**
```json
{
    "firstTwo": "$.array[0:2]",
    "lastTwo": "$.array[-2:]",
    "middle": "$.array[1:-1]"
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

### 4. Array Filtering

Filter array elements based on conditions.

**Input JSON:**
```json
{
    "items": [
        {"id": 1, "value": 10, "active": true},
        {"id": 2, "value": 20, "active": false},
        {"id": 3, "value": 30, "active": true}
    ]
}
```

**Mapping Rules:**
```json
{
    "activeItems": "$.items[?(@.active == true)]",
    "highValue": "$.items[?(@.value > 15)]"
}
```

**Output JSON:**
```json
{
    "activeItems": [
        {"id": 1, "value": 10, "active": true},
        {"id": 3, "value": 30, "active": true}
    ],
    "highValue": [
        {"id": 2, "value": 20, "active": false},
        {"id": 3, "value": 30, "active": true}
    ]
}
```

## Advanced Array Mapping

### 1. Complex Nested Array Mapping

Demonstrates complex array mapping with multiple transformation types.

**Input JSON:**
```json
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
                }
            ],
            "totalAmount": 2475.00
        }
    ]
}
```

**Mapping Rules:**
```json
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
                    "productName": "$.product.name",
                    "category": {
                        "type": "value",
                        "sourcePath": "$.product.category",
                        "mappings": [
                            {"source": "electronics", "target": "Electronics & Gadgets"}
                        ]
                    },
                    "quantity": "$.quantity",
                    "total": "$.lineTotal"
                }
            }
        }
    }
}
```

**Output JSON:**
```json
{
    "orders": [
        {
            "orderId": "ORD-1",
            "orderDate": "2024-03-15",
            "orderAmount": 2475.00,
            "orderType": "HIGH_VALUE",
            "lines": [
                {
                    "id": "LINE-1",
                    "productName": "Laptop",
                    "category": "Electronics & Gadgets",
                    "quantity": 2,
                    "total": 2400.00
                }
            ]
        }
    ]
}
```

### 2. Single Object to Array Wrapping

Convert a single object into an array using wrapAsArray option.

**Input JSON:**
```json
{
    "product": {
        "id": "P123",
        "name": "Laptop"
    }
}
```

**Mapping Rules:**
```json
{
    "products": {
        "type": "array",
        "sourcePath": "$.product",
        "wrapAsArray": true,
        "itemMapping": {
            "productId": "$.id",
            "productName": "$.name"
        }
    }
}
```

**Output JSON:**
```json
{
    "products": [
        {
            "productId": "P123",
            "productName": "Laptop"
        }
    ]
}
```

## Error Handling

- **Invalid Paths**: Throws JsonTransformationException for invalid JSONPath expressions
- **Out of Bounds**: Returns null for single element access, empty array for slices
- **Null Values**: Preserves null values in arrays
- **Missing Fields**: Returns null for non-existent fields
- **Invalid Arrays**: Throws JsonTransformationException for invalid array operations
