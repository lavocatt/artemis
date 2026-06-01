/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.artemis.jsonschema.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.activemq.artemis.core.config.impl.JsonExporterSpi;

/**
 * Schema-driven flat-properties-to-JSON transformer. Uses the broker JSON Schema
 * to reconstruct properly typed JSON from flat dotted-key broker properties.
 *
 * Without the schema, "maxDeliveryAttempts=10" is ambiguous (string or integer?).
 * With the schema, the type is known at every path.
 */
public class PropertiesToJsonExporter implements JsonExporterSpi {

   private static final String SCHEMA_RESOURCE = "/org.apache.artemis/jsonschema/broker-config-schema.json";
   private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
   private static final Pattern QUOTED_SEGMENT = Pattern.compile("\"([^\"]+)\"");

   private static volatile Map<String, Object> cachedResolvedSchema;

   @Override
   public String toJson(LinkedHashMap<String, String> properties) throws Exception {
      Map<String, Object> resolvedSchema = getResolvedSchema();
      ObjectNode root = MAPPER.createObjectNode();

      for (Map.Entry<String, String> entry : properties.entrySet()) {
         String key = entry.getKey();
         String value = entry.getValue();
         List<String> segments = splitKey(key);

         insertValue(root, segments, 0, value, resolvedSchema);
      }

      return MAPPER.writeValueAsString(root);
   }

   @SuppressWarnings("unchecked")
   private void insertValue(ObjectNode current, List<String> segments, int depth,
                            String value, Map<String, Object> schemaContext) {
      if (depth >= segments.size()) {
         return;
      }

      String segment = segments.get(depth);
      boolean isLeaf = (depth == segments.size() - 1);

      if (isLeaf) {
         String schemaType = resolveType(segment, schemaContext);
         setTypedValue(current, segment, value, schemaType);
      } else {
         ObjectNode child;
         if (current.has(segment) && current.get(segment).isObject()) {
            child = (ObjectNode) current.get(segment);
         } else {
            child = MAPPER.createObjectNode();
            current.set(segment, child);
         }

         Map<String, Object> childSchemaContext = resolveChildSchema(segment, schemaContext);
         insertValue(child, segments, depth + 1, value, childSchemaContext);
      }
   }

   @SuppressWarnings("unchecked")
   private String resolveType(String propertyName, Map<String, Object> schemaContext) {
      if (schemaContext == null) {
         return "string";
      }

      Map<String, Object> properties = (Map<String, Object>) schemaContext.get("properties");
      if (properties != null && properties.containsKey(propertyName)) {
         Map<String, Object> propSchema = (Map<String, Object>) properties.get(propertyName);
         return extractType(propSchema);
      }

      Map<String, Object> addProps = getAdditionalPropertiesSchema(schemaContext);
      if (addProps != null) {
         Map<String, Object> addProperties = (Map<String, Object>) addProps.get("properties");
         if (addProperties != null && addProperties.containsKey(propertyName)) {
            Map<String, Object> propSchema = (Map<String, Object>) addProperties.get(propertyName);
            return extractType(propSchema);
         }
      }

      return "string";
   }

   @SuppressWarnings("unchecked")
   private Map<String, Object> resolveChildSchema(String segment, Map<String, Object> schemaContext) {
      if (schemaContext == null) {
         return null;
      }

      Map<String, Object> properties = (Map<String, Object>) schemaContext.get("properties");
      if (properties != null && properties.containsKey(segment)) {
         return (Map<String, Object>) properties.get(segment);
      }

      Object addPropsRaw = schemaContext.get("additionalProperties");
      if (addPropsRaw instanceof Map) {
         return (Map<String, Object>) addPropsRaw;
      }

      return null;
   }

   @SuppressWarnings("unchecked")
   private Map<String, Object> getAdditionalPropertiesSchema(Map<String, Object> schema) {
      Object addProps = schema.get("additionalProperties");
      return addProps instanceof Map ? (Map<String, Object>) addProps : null;
   }

   @SuppressWarnings("unchecked")
   private String extractType(Map<String, Object> propSchema) {
      if (propSchema == null) {
         return "string";
      }

      Object type = propSchema.get("type");
      if (type instanceof String) {
         return (String) type;
      }
      if (type instanceof List) {
         List<String> types = (List<String>) type;
         for (String t : types) {
            if (!"null".equals(t)) {
               return t;
            }
         }
      }

      if (propSchema.containsKey("enum")) {
         return "string";
      }

      return "string";
   }

   private void setTypedValue(ObjectNode node, String key, String value, String schemaType) {
      if (value == null || value.isEmpty()) {
         node.put(key, "");
         return;
      }

      switch (schemaType) {
         case "integer" -> {
            try {
               node.put(key, Long.parseLong(value));
            } catch (NumberFormatException e) {
               node.put(key, value);
            }
         }
         case "number" -> {
            try {
               node.put(key, Double.parseDouble(value));
            } catch (NumberFormatException e) {
               node.put(key, value);
            }
         }
         case "boolean" -> node.put(key, Boolean.parseBoolean(value));
         case "array" -> {
            ArrayNode arr = MAPPER.createArrayNode();
            for (String element : value.split(",")) {
               arr.add(element.trim());
            }
            node.set(key, arr);
         }
         default -> node.put(key, value);
      }
   }

   static List<String> splitKey(String key) {
      List<String> segments = new ArrayList<>();
      StringBuilder current = new StringBuilder();
      boolean inQuotes = false;

      for (int i = 0; i < key.length(); i++) {
         char c = key.charAt(i);
         if (c == '"') {
            inQuotes = !inQuotes;
         } else if (c == '.' && !inQuotes) {
            if (!current.isEmpty()) {
               segments.add(current.toString());
               current.setLength(0);
            }
         } else {
            current.append(c);
         }
      }
      if (!current.isEmpty()) {
         segments.add(current.toString());
      }

      return segments;
   }

   @SuppressWarnings("unchecked")
   private static Map<String, Object> getResolvedSchema() throws Exception {
      if (cachedResolvedSchema != null) {
         return cachedResolvedSchema;
      }
      synchronized (PropertiesToJsonExporter.class) {
         if (cachedResolvedSchema != null) {
            return cachedResolvedSchema;
         }
         InputStream is = PropertiesToJsonExporter.class.getResourceAsStream(SCHEMA_RESOURCE);
         if (is == null) {
            throw new IllegalStateException(
               "JSON schema not found on classpath: " + SCHEMA_RESOURCE +
               ". Ensure artemis-jsonschema JAR (built with -Pgenerate-schema) is on the classpath.");
         }
         Map<String, Object> rawSchema = MAPPER.readValue(is, Map.class);
         cachedResolvedSchema = SchemaResolver.resolveSchema(rawSchema);
         return cachedResolvedSchema;
      }
   }
}
