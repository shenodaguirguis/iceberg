/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.JsonUtil;


public class SchemaParser {

  private SchemaParser() {
  }

  private static final String TYPE = "type";
  private static final String STRUCT = "struct";
  private static final String LIST = "list";
  private static final String MAP = "map";
  private static final String FIELDS = "fields";
  private static final String ELEMENT = "element";
  private static final String KEY = "key";
  private static final String VALUE = "value";
  private static final String DOC = "doc";
  private static final String NAME = "name";
  private static final String ID = "id";
  private static final String ELEMENT_ID = "element-id";
  private static final String KEY_ID = "key-id";
  private static final String VALUE_ID = "value-id";
  private static final String REQUIRED = "required";
  private static final String ELEMENT_REQUIRED = "element-required";
  private static final String VALUE_REQUIRED = "value-required";
  private static final String DEFAULT = "default";

  private static final List<Class> primitiveClasses = Arrays.asList(Boolean.class, Integer.class, Long.class,
      Float.class, Double.class, CharSequence.class, String.class, java.util.UUID.class, BigDecimal.class);

  private static void writeDefaultValue(Object defaultValue, Type type, JsonGenerator generator) throws IOException {
    if (defaultValue == null) {
      return;
    }
    generator.writeFieldName(DEFAULT);
    if (type.isListType()) {
      generator.writeString(defaultValueToJsonString((List<Object>) defaultValue));
    } else if (type.isStructType() || type.isMapType()) {
      generator.writeString(defaultValueToJsonString((Map<String, Object>) defaultValue));
    } else if (isFixedOrBinary(type)) {
      generator.writeString(defaultValueToJsonString((byte[]) defaultValue));
    } else {
      generator.writeString(defaultValueToJsonString(defaultValue));
    }
  }

  static void toJson(Types.StructType struct, JsonGenerator generator) throws IOException {
    generator.writeStartObject();

    generator.writeStringField(TYPE, STRUCT);
    generator.writeArrayFieldStart(FIELDS);
    for (Types.NestedField field : struct.fields()) {
      generator.writeStartObject();
      generator.writeNumberField(ID, field.fieldId());
      generator.writeStringField(NAME, field.name());
      generator.writeBooleanField(REQUIRED, field.isRequired());
      generator.writeFieldName(TYPE);
      toJson(field.type(), generator);
      writeDefaultValue(field.getDefaultValue(), field.type(), generator);
      if (field.doc() != null) {
        generator.writeStringField(DOC, field.doc());
      }

      generator.writeEndObject();
    }
    generator.writeEndArray();
    generator.writeEndObject();
  }

  static void toJson(Types.ListType list, JsonGenerator generator) throws IOException {
    generator.writeStartObject();

    generator.writeStringField(TYPE, LIST);

    generator.writeNumberField(ELEMENT_ID, list.elementId());
    generator.writeFieldName(ELEMENT);
    toJson(list.elementType(), generator);
    generator.writeBooleanField(ELEMENT_REQUIRED, !list.isElementOptional());
    generator.writeEndObject();
  }

  static void toJson(Types.MapType map, JsonGenerator generator) throws IOException {
    generator.writeStartObject();

    generator.writeStringField(TYPE, MAP);

    generator.writeNumberField(KEY_ID, map.keyId());
    generator.writeFieldName(KEY);
    toJson(map.keyType(), generator);

    generator.writeNumberField(VALUE_ID, map.valueId());
    generator.writeFieldName(VALUE);
    toJson(map.valueType(), generator);
    generator.writeBooleanField(VALUE_REQUIRED, !map.isValueOptional());

    generator.writeEndObject();
  }

  static void toJson(Type.PrimitiveType primitive, JsonGenerator generator) throws IOException {
    generator.writeString(primitive.toString());
  }

  static void toJson(Type type, JsonGenerator generator) throws IOException {
    if (type.isPrimitiveType()) {
      toJson(type.asPrimitiveType(), generator);
    } else {
      Type.NestedType nested = type.asNestedType();
      switch (type.typeId()) {
        case STRUCT:
          toJson(nested.asStructType(), generator);
          break;
        case LIST:
          toJson(nested.asListType(), generator);
          break;
        case MAP:
          toJson(nested.asMapType(), generator);
          break;
        default:
          throw new IllegalArgumentException("Cannot write unknown type: " + type);
      }
    }
  }

  public static void toJson(Schema schema, JsonGenerator generator) throws IOException {
    toJson(schema.asStruct(), generator);
  }

  public static String toJson(Schema schema) {
    return toJson(schema, false);
  }

  public static String toJson(Schema schema, boolean pretty) {
    try {
      StringWriter writer = new StringWriter();
      JsonGenerator generator = JsonUtil.factory().createGenerator(writer);
      if (pretty) {
        generator.useDefaultPrettyPrinter();
      }
      toJson(schema.asStruct(), generator);
      generator.flush();
      return writer.toString();

    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
  }

  private static Type typeFromJson(JsonNode json) {
    if (json.isTextual()) {
      return Types.fromPrimitiveString(json.asText());

    } else if (json.isObject()) {
      String type = json.get(TYPE).asText();
      if (STRUCT.equals(type)) {
        return structFromJson(json);
      } else if (LIST.equals(type)) {
        return listFromJson(json);
      } else if (MAP.equals(type)) {
        return mapFromJson(json);
      }
    }

    throw new IllegalArgumentException("Cannot parse type from json: " + json);
  }

  private static boolean isFixedOrBinary(Type type) {
    return type.typeId() == Type.TypeID.FIXED || type.typeId() == Type.TypeID.BINARY;
  }

  private static Object defaultValueFromJson(JsonNode field, Type type) {
    if (!field.has(DEFAULT)) {
      return null;
    }

    String defaultValueString = field.get(DEFAULT).asText();

    if (isFixedOrBinary(type)) {
      return defaultValueFromJsonBytesField(defaultValueString);
    }

    if (type.isPrimitiveType()) {
      return primitiveDefaultValueFromJsonString(defaultValueString, type);
    }

    try {
      return defaultValueFromJsonString(defaultValueString, type);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static Types.StructType structFromJson(JsonNode json) {
    JsonNode fieldArray = json.get(FIELDS);
    Preconditions.checkArgument(fieldArray.isArray(),
        "Cannot parse struct fields from non-array: %s", fieldArray);

    List<Types.NestedField> fields = Lists.newArrayListWithExpectedSize(fieldArray.size());
    Iterator<JsonNode> iterator = fieldArray.elements();
    while (iterator.hasNext()) {
      JsonNode field = iterator.next();
      Preconditions.checkArgument(field.isObject(),
          "Cannot parse struct field from non-object: %s", field);

      int id = JsonUtil.getInt(ID, field);
      String name = JsonUtil.getString(NAME, field);
      Type type = typeFromJson(field.get(TYPE));
      Object defaultValue = defaultValueFromJson(field, type);
      String doc = JsonUtil.getStringOrNull(DOC, field);
      boolean isRequired = JsonUtil.getBool(REQUIRED, field);
      if (isRequired) {
        fields.add(Types.NestedField.required(id, name, type, defaultValue, doc));
      } else {
        fields.add(Types.NestedField.optional(id, name, type, defaultValue, doc));
      }
    }

    return Types.StructType.of(fields);
  }


  private static Types.ListType listFromJson(JsonNode json) {
    int elementId = JsonUtil.getInt(ELEMENT_ID, json);
    Type elementType = typeFromJson(json.get(ELEMENT));
    boolean isRequired = JsonUtil.getBool(ELEMENT_REQUIRED, json);

    if (isRequired) {
      return Types.ListType.ofRequired(elementId, elementType);
    } else {
      return Types.ListType.ofOptional(elementId, elementType);
    }
  }

  private static Types.MapType mapFromJson(JsonNode json) {
    int keyId = JsonUtil.getInt(KEY_ID, json);
    Type keyType = typeFromJson(json.get(KEY));

    int valueId = JsonUtil.getInt(VALUE_ID, json);
    Type valueType = typeFromJson(json.get(VALUE));

    boolean isRequired = JsonUtil.getBool(VALUE_REQUIRED, json);

    if (isRequired) {
      return Types.MapType.ofRequired(keyId, valueId, keyType, valueType);
    } else {
      return Types.MapType.ofOptional(keyId, valueId, keyType, valueType);
    }
  }

  public static Schema fromJson(JsonNode json) {
    Type type  = typeFromJson(json);
    Preconditions.checkArgument(type.isNestedType() && type.asNestedType().isStructType(),
        "Cannot create schema, not a struct type: %s", type);
    return new Schema(type.asNestedType().asStructType().fields());
  }

  private static final Cache<String, Schema> SCHEMA_CACHE = Caffeine.newBuilder()
      .weakValues()
      .build();

  public static Schema fromJson(String json) {
    return SCHEMA_CACHE.get(json, jsonKey -> {
      try {
        return fromJson(JsonUtil.mapper().readValue(jsonKey, JsonNode.class));
      } catch (IOException e) {
        throw new RuntimeIOException(e);
      }
    });
  }

  private static String defaultValueToJsonString(Map<String, Object> map) {
    Map<String, String> jsonStringElementsMap = new LinkedHashMap<>();
    map.entrySet().forEach(
        entry -> jsonStringElementsMap.put(entry.getKey(), defaultValueToJsonString(entry.getValue())));
    return defaultValueToJsonString(jsonStringElementsMap);
  }

  private static String defaultValueToJsonString(List<Object> list) {
    List<String> jsonStringItemsList = new ArrayList<>();
    list.forEach(item -> jsonStringItemsList.add(defaultValueToJsonString(item)));
    return defaultValueToJsonString(jsonStringItemsList);
  }

  private static String defaultValueToJsonString(byte[] bytes) {
    try {
      return JsonUtil.mapper().writeValueAsString(ByteBuffer.wrap(bytes));
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  private static String defaultValueToJsonString(Object value) {
    if (isPrimitiveClass(value)) {
      return value.toString();
    }

    try {
      return JsonUtil.mapper().writeValueAsString(new SerDeValue(value));
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean isPrimitiveClass(Object value) {
    return primitiveClasses.contains(value.getClass());
  }

  private static Object defaultValueFromJsonBytesField(String value) {
    try {
      return JsonUtil.mapper().readValue(value, ByteBuffer.class).array();
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  private static Object defaultValueFromJsonString(String jsonString, Type type) throws IOException {
    Preconditions.checkArgument(!type.isPrimitiveType(), "jsonString %s is for primitive type %s", jsonString, type);
    Object jsonStringCollection = JsonUtil.mapper().readValue(jsonString, SerDeValue.class).getValue();

    if (type.isListType()) {
      Preconditions.checkArgument(jsonStringCollection instanceof List,
          "deserialized Json object: (%s) is not List for List type", jsonStringCollection);
      List<Object> list = new ArrayList<>();
      Type elementType = type.asListType().elementType();
      for (String item : (List<String>) jsonStringCollection) {
        list.add(elementType.isPrimitiveType() ? primitiveDefaultValueFromJsonString(item, elementType) :
            JsonUtil.mapper().readValue(item, SerDeValue.class).getValue());
      }
      return list;
    }

    Preconditions.checkArgument((type.isMapType() || type.isStructType()) && jsonStringCollection instanceof Map,
        "deserialized Json object: (%s) is not Map for type: %s", jsonStringCollection, type);

    // map (MapType or StructType) case
    Map<String, Object> map = new HashMap<>();
    Map<String, String> jsonStringMap = (HashMap<String, String>) jsonStringCollection;
    for (Map.Entry entry : jsonStringMap.entrySet()) {
      String key = entry.getKey().toString();
      String valueString = entry.getValue().toString();
      Type elementType = type.isMapType() ? type.asMapType().valueType() : type.asStructType().field(key).type();
      Object value = elementType.isPrimitiveType() ? primitiveDefaultValueFromJsonString(valueString, elementType)
            : JsonUtil.mapper().readValue(valueString, SerDeValue.class).getValue();
      map.put(key, value);
    }
    return map;
  }

  private static Object primitiveDefaultValueFromJsonString(String jsonString, Type type) {
    switch (type.typeId()) {
      case BOOLEAN:
        return Boolean.valueOf(jsonString);
      case INTEGER:
      case DATE:
        return Integer.valueOf(jsonString);
      case DECIMAL:
        return BigDecimal.valueOf(Long.valueOf(jsonString));
      case LONG:
      case TIME:
      case TIMESTAMP:
        return Long.valueOf(jsonString);
      case FLOAT:
        return Float.valueOf(jsonString);
      case DOUBLE:
        return Double.valueOf(jsonString);
      case STRING:
        return jsonString;
      case UUID:
        return java.util.UUID.fromString(jsonString);
      case FIXED:
      case BINARY:
        return defaultValueFromJsonBytesField(jsonString);
      default:
        throw new RuntimeException("non-primitive type: " + type);
    }
  }

  /**
   * SerDeValue class:
   *   This is used so that the value to serialize is specified
   *   as a property, so that the type information gets included in
   *   the serialized String.
   */
  private static class SerDeValue {
    // Name of the field used in the intermediate JSON representation
    private static final String VALUE_FIELD = "__value__";

    @JsonProperty(VALUE_FIELD)
    private final Object value;

    @JsonCreator
    private SerDeValue(@JsonProperty(VALUE_FIELD) Object value) {
      this.value = value;
    }

    private Object getValue() {
      return value;
    }
  }
}
