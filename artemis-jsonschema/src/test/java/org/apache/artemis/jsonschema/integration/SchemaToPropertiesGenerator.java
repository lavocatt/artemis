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

package org.apache.artemis.jsonschema.integration;

import java.util.*;

/**
 * Walks a JSON Schema and generates a Properties object with valid test values for each
 * property path. Used by ConfigRoundTripTest to prove the schema describes real config.
 */
public class SchemaToPropertiesGenerator {

   private static final Set<String> IGNORED_FOR_EXPORT = Set.of(
         "status", "securityRoleNameMappings", "queueConfigurations", "queueConfigs",
         "encodeSize", "federationPolicyMap", "policySets", "AMQPConnection",
         "AMQPConnectionConfigurations", "combinedParams", "type", "uri",
         "parent", "connectionElements"
   );

   private final Map<String, Object> schema;
   private final Properties generated = new Properties();
   private final List<String> skipped = new ArrayList<>();

   public SchemaToPropertiesGenerator(Map<String, Object> schema) {
      this.schema = schema;
   }

   /**
    * Generate properties from the schema.
    *
    * @return Properties with valid test values for exportable paths
    */
   public Properties generate() {
      Map<String, Object> defs = getMap(schema, "$defs");
      Map<String, Object> properties = getMap(schema, "properties");

      if (properties != null) {
         for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String propName = entry.getKey();
            if (IGNORED_FOR_EXPORT.contains(propName)) {
               skipped.add(propName + " (in export ignore list)");
               continue;
            }
            if (entry.getValue() instanceof Map) {
               @SuppressWarnings("unchecked")
               Map<String, Object> propSchema = (Map<String, Object>) entry.getValue();
               generateProperty(propName, propSchema, defs, 0);
            }
         }
      }
      return generated;
   }

   public List<String> getSkipped() {
      return skipped;
   }

   @SuppressWarnings("unchecked")
   private void generateProperty(String path, Map<String, Object> node, Map<String, Object> defs, int depth) {
      if (depth > 6) return;

      // Resolve $ref
      if (node.containsKey("$ref")) {
         String ref = (String) node.get("$ref");
         String defName = ref.substring(ref.lastIndexOf('/') + 1);
         if (defs != null && defs.containsKey(defName)) {
            generateProperty(path, (Map<String, Object>) defs.get(defName), defs, depth + 1);
         }
         return;
      }

      // Handle oneOf — pick first branch
      if (node.containsKey("oneOf")) {
         List<Map<String, Object>> oneOf = (List<Map<String, Object>>) node.get("oneOf");
         if (!oneOf.isEmpty()) {
            generateProperty(path, oneOf.get(0), defs, depth + 1);
         }
         return;
      }

      String access = (String) node.get("x-access");
      if ("RO".equals(access)) {
         skipped.add(path + " (read-only)");
         return;
      }

      Object type = node.get("type");
      String typeStr = type instanceof String ? (String) type : (type instanceof List ? ((List<?>) type).get(0).toString() : "string");

      switch (typeStr) {
         case "string" -> {
            if (node.containsKey("const")) {
               generated.setProperty(path, (String) node.get("const"));
            } else if (node.containsKey("enum")) {
               List<String> enumValues = (List<String>) node.get("enum");
               generated.setProperty(path, enumValues.get(0));
            } else {
               generated.setProperty(path, "test-value");
            }
         }
         case "integer" -> generated.setProperty(path, "42");
         case "boolean" -> generated.setProperty(path, "true");
         case "number" -> generated.setProperty(path, "3.14");
         case "array" -> {
            // Comma-separated for broker.properties
            Map<String, Object> items = getMap(node, "items");
            if (items != null && items.containsKey("enum")) {
               List<String> enums = (List<String>) items.get("enum");
               generated.setProperty(path, enums.get(0));
            } else {
               generated.setProperty(path, "test-item1,test-item2");
            }
         }
         case "object" -> {
            Map<String, Object> props = getMap(node, "properties");
            Map<String, Object> addProps = getMap(node, "additionalProperties");

            if (props != null && !props.isEmpty()) {
               // Object with known properties — recurse into each
               for (Map.Entry<String, Object> entry : props.entrySet()) {
                  String childName = entry.getKey();
                  if (IGNORED_FOR_EXPORT.contains(childName)) {
                     skipped.add(path + "." + childName + " (in export ignore list)");
                     continue;
                  }
                  if (entry.getValue() instanceof Map) {
                     generateProperty(path + "." + childName, (Map<String, Object>) entry.getValue(), defs, depth + 1);
                  }
               }
            } else if (addProps != null && addProps instanceof Map) {
               // Map-like: generate one sample entry
               String sampleKey = "testEntry1";
               if (addProps.containsKey("oneOf")) {
                  // Polymorphic map — pick first branch
                  List<Map<String, Object>> oneOf = (List<Map<String, Object>>) addProps.get("oneOf");
                  if (!oneOf.isEmpty()) {
                     generateProperty(path + "." + sampleKey, oneOf.get(0), defs, depth + 1);
                  }
               } else if (addProps.containsKey("$ref")) {
                  String ref = (String) addProps.get("$ref");
                  String defName = ref.substring(ref.lastIndexOf('/') + 1);
                  if (defs != null && defs.containsKey(defName)) {
                     generateProperty(path + "." + sampleKey, (Map<String, Object>) defs.get(defName), defs, depth + 1);
                  }
               } else if (addProps.containsKey("type")) {
                  String addType = (String) addProps.get("type");
                  if ("string".equals(addType)) {
                     generated.setProperty(path + "." + sampleKey, "test-map-value");
                  } else {
                     generateProperty(path + "." + sampleKey, (Map<String, Object>) addProps, defs, depth + 1);
                  }
               }
            }
         }
      }
   }

   @SuppressWarnings("unchecked")
   private Map<String, Object> getMap(Map<String, Object> parent, String key) {
      Object value = parent.get(key);
      if (value instanceof Map) {
         return (Map<String, Object>) value;
      }
      return null;
   }
}
