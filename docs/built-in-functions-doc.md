# Built-in Functions Feature

## Overview

Built-in functions provide powerful data transformation capabilities in the JSON Integration Mapper. These functions allow you to manipulate strings, numbers, dates, and perform utility operations during the mapping process.

## Basic Structure

A function mapping uses the following structure:

```json
{
    "targetField": {
        "type": "function",
        "function": "$functionName",
        "sourcePath": "$.path.to.source",
        "args": ["arg1", "arg2"]  // Optional arguments
    }
}
```

## String Functions

### 1. $string
Converts input to string representation.

**Input JSON:**
```json
{
    "number": 42
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "function",
        "function": "$string",
        "sourcePath": "$.number"
    }
}
```

**Output JSON:**
```json
{
    "result": "42"
}
```

### 2. $uppercase
Converts string to uppercase.

**Input JSON:**
```json
{
    "text": "Hello World"
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "function",
        "function": "$uppercase",
        "sourcePath": "$.text"
    }
}
```

**Output JSON:**
```json
{
    "result": "HELLO WORLD"
}
```

### 3. $lowercase
Converts string to lowercase.

**Input JSON:**
```json
{
    "text": "Hello World"
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "function",
        "function": "$lowercase",
        "sourcePath": "$.text"
    }
}
```

**Output JSON:**
```json
{
    "result": "hello world"
}
```

### 4. $trim
Removes leading and trailing whitespace.

**Input JSON:**
```json
{
    "text": "  Hello World  "
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "function",
        "function": "$trim",
        "sourcePath": "$.text"
    }
}
```

**Output JSON:**
```json
{
    "result": "Hello World"
}
```

### 5. $substring
Extracts part of a string.

**Input JSON:**
```json
{
    "text": "Hello World"
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "function",
        "function": "$substring",
        "sourcePath": "$.text",
        "args": [0, 5]
    }
}
```

**Output JSON:**
```json
{
    "result": "Hello"
}
```

## Numeric Functions

### 1. $number
Converts string to number.

**Input JSON:**
```json
{
    "value": "42.5"
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "function",
        "function": "$number",
        "sourcePath": "$.value"
    }
}
```

**Output JSON:**
```json
{
    "result": 42.5
}
```

### 2. $round
Rounds number to specified decimal places.

**Input JSON:**
```json
{
    "value": 42.567
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "function",
        "function": "$round",
        "sourcePath": "$.value",
        "args": [2]
    }
}
```

**Output JSON:**
```json
{
    "result": 42.57
}
```

### 3. $sum
Adds numbers in an array.

**Input JSON:**
```json
{
    "numbers": [1, 2, 3, 4, 5]
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "function",
        "function": "$sum",
        "sourcePath": "$.numbers"
    }
}
```

**Output JSON:**
```json
{
    "result": 15
}
```

## Date Functions

### 1. $now
Returns current date and time in ISO format.

**Input JSON:**
```json
{
    "dummy": "value"
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "function",
        "function": "$now"
    }
}
```

**Output JSON:**
```json
{
    "result": "2024-12-11T10:30:00Z"  // Current time
}
```

### 2. $formatDate
Formats date string to ISO8601 format.

**Input JSON:**
```json
{
    "date": "2023-12-31T23:59:59Z"
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "function",
        "function": "$formatDate",
        "sourcePath": "$.date"
    }
}
```

**Output JSON:**
```json
{
    "result": "2023-12-31T23:59:59Z"
}
```

## Utility Functions

### 1. $uuid
Generates a UUID.

**Input JSON:**
```json
{
    "dummy": "value"
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "function",
        "function": "$uuid"
    }
}
```

**Output JSON:**
```json
{
    "result": "550e8400-e29b-41d4-a716-446655440000" // Random UUID
}
```

### 2. $concat
Concatenates strings and can include JsonPath references.

**Basic Concatenation:**

**Input JSON:**
```json
{
    "text": "Hello"
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "function",
        "function": "$concat",
        "sourcePath": "$.text",
        "args": [" ", "World"]
    }
}
```

**Output JSON:**
```json
{
    "result": "Hello World"
}
```

**With JsonPath References:**

**Input JSON:**
```json
{
    "greeting": "Hello",
    "name": "World"
}
```

**Mapping Rules:**
```json
{
    "result": {
        "type": "function",
        "function": "$concat",
        "args": ["$.greeting", " ", "$.name"]
    }
}
```

**Output JSON:**
```json
{
    "result": "Hello World"
}
```

## Error Handling

- Functions handle null inputs gracefully:
  - `$string` returns empty string for null
  - `$number` returns null for invalid numbers
  - `$formatDate` throws JsonTransformationException for invalid dates

- Out of bounds handling:
  - `$substring` safely handles indices beyond string length
  - `$sum` ignores non-numeric values in arrays

- Invalid inputs:
  - Functions validate their inputs and throw appropriate exceptions
  - Clear error messages help in debugging
