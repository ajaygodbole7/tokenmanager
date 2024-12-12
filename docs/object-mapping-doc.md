# Object Mapping Feature

## Overview

Object mapping allows you to transform complex nested JSON objects by creating new object structures and mapping fields from the source JSON. This feature is particularly useful when you need to restructure your JSON data while preserving the hierarchical nature of the objects.

## Key Features

- **Nested Object Transformation**: Map complex nested object structures
- **Field Renaming**: Change field names during transformation
- **Null Handling**: Proper handling of null values in nested objects
- **Path Resolution**: Graceful handling of invalid paths
- **Type Safety**: Required type specification for object mappings

## Basic Structure

An object mapping uses the following structure:

```json
{
    "targetField": {
        "type": "object",
        "field1": "$.sourcePath1",
        "field2": "$.sourcePath2",
        "nestedObject": {
            "type": "object",
            "nestedField1": "$.sourcePath3"
        }
    }
}
```

## Examples

### 1. Basic Nested Object Mapping

Maps a complex nested object structure.

**Input JSON:**
```json
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
```

**Mapping Rules:**
```json
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
```

**Output JSON:**
```json
{
    "result": {
        "userId": "12345",
        "fullName": {
            "first": "John",
            "last": "Doe"
        },
        "location": {
            "city": "New York",
            "state": "NY"
        }
    }
}
```

### 2. Handling Null Values

Demonstrates how null values are handled in nested objects.

**Input JSON:**
```json
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
```

**Mapping Rules:**
```json
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
```

**Output JSON:**
```json
{
    "result": {
        "userId": null,
        "fullName": {
            "first": null,
            "last": "Doe"
        },
        "location": {
            "city": null,
            "state": null
        }
    }
}
```

### 3. Empty Object Mapping

Shows how empty object mappings are handled.

**Input JSON:**
```json
{
    "details": {
        "id": "12345"
    }
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "object"
    }
}
```

**Output JSON:**
```json
{
    "result": {}
}
```

### 4. Invalid Path Handling

Demonstrates handling of invalid source paths.

**Input JSON:**
```json
{
    "details": {
        "id": "12345"
    }
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "object",
        "userId": "$.invalidPath"
    }
}
```

**Output JSON:**
```json
{
    "result": {
        "userId": null
    }
}
```

## Error Handling

### Type Field Requirement

The mapper requires a "type" field in object mappings. Omitting it will result in an error.

**Invalid Mapping (Will Throw Error):**
```json
{
    "result": {
        "userId": "$.details.id"
    }
}
```

**Error:**
- Throws JsonTransformationException
- Error message indicates missing required field 'type'

### Other Error Handling Features

1. **Null Values**:
   - Null values in source JSON are preserved in output
   - Null parent objects result in null child fields
   - Missing fields are represented as null in output

2. **Invalid Paths**:
   - Invalid JSONPath expressions resolve to null
   - Mapping continues for other valid fields
   - No exception thrown for invalid paths

3. **Empty Objects**:
   - Empty object mappings result in empty JSON objects
   - All fields properly initialized even if empty
