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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves JSON Schema composition keywords ($ref, allOf, oneOf) to produce
 * a fully expanded schema tree. For oneOf, unions all alternatives so the
 * transformer can then match against instance data.
 *
 * Ported from broker-properties-explorer SchemaResolver.
 */
public class SchemaResolver {

   private final Set<String> resolutionStack = new HashSet<>();

   @SuppressWarnings("unchecked")
   public static Map<String, Object> resolveSchema(Map<String, Object> schema) {
      Map<String, Object> defs = (Map<String, Object>) schema.get("$defs");
      if (defs == null) {
         defs = new LinkedHashMap<>();
      }
      return new SchemaResolver().resolveNode(new LinkedHashMap<>(schema), defs);
   }

   @SuppressWarnings("unchecked")
   private Map<String, Object> resolveNode(Map<String, Object> node, Map<String, Object> defs) {
      if (node == null) {
         return null;
      }

      Map<String, Object> resolved = new LinkedHashMap<>(node);

      if (resolved.containsKey("$ref")) {
         String ref = resolved.get("$ref").toString();
         if (resolutionStack.contains(ref)) {
            resolved.remove("$ref");
            return resolved;
         }
         resolutionStack.add(ref);
         Map<String, Object> refNode = resolveRef(ref, defs);
         if (refNode != null) {
            Map<String, Object> refResolved = resolveNode(new LinkedHashMap<>(refNode), defs);
            for (Map.Entry<String, Object> entry : refResolved.entrySet()) {
               if (!"$ref".equals(entry.getKey())) {
                  resolved.putIfAbsent(entry.getKey(), entry.getValue());
               }
            }
         }
         resolutionStack.remove(ref);
         resolved.remove("$ref");
      }

      if (resolved.containsKey("allOf")) {
         List<Map<String, Object>> allOfList = (List<Map<String, Object>>) resolved.get("allOf");
         Map<String, Object> merged = mergeSchemas(allOfList, defs);
         for (Map.Entry<String, Object> entry : merged.entrySet()) {
            if ("properties".equals(entry.getKey())) {
               Map<String, Object> existing = (Map<String, Object>) resolved.get("properties");
               if (existing == null) {
                  resolved.put("properties", entry.getValue());
               } else {
                  existing.putAll((Map<String, Object>) entry.getValue());
               }
            } else {
               resolved.putIfAbsent(entry.getKey(), entry.getValue());
            }
         }
         resolved.remove("allOf");
      }

      // Keep oneOf in the resolved schema so the transformer can do instance-based matching.
      // But resolve each alternative's internal refs.
      if (resolved.containsKey("oneOf")) {
         List<Map<String, Object>> oneOfList = (List<Map<String, Object>>) resolved.get("oneOf");
         List<Map<String, Object>> resolvedAlts = new ArrayList<>();
         for (Map<String, Object> alt : oneOfList) {
            resolvedAlts.add(resolveNode(new LinkedHashMap<>(alt), defs));
         }
         resolved.put("oneOf", resolvedAlts);
      }

      if (resolved.containsKey("properties") && resolved.get("properties") instanceof Map) {
         Map<String, Object> props = (Map<String, Object>) resolved.get("properties");
         Map<String, Object> resolvedProps = new LinkedHashMap<>();
         for (Map.Entry<String, Object> entry : props.entrySet()) {
            if (entry.getValue() instanceof Map) {
               resolvedProps.put(entry.getKey(), resolveNode((Map<String, Object>) entry.getValue(), defs));
            } else {
               resolvedProps.put(entry.getKey(), entry.getValue());
            }
         }
         resolved.put("properties", resolvedProps);
      }

      if (resolved.containsKey("items") && resolved.get("items") instanceof Map) {
         resolved.put("items", resolveNode((Map<String, Object>) resolved.get("items"), defs));
      }

      if (resolved.containsKey("additionalProperties") && resolved.get("additionalProperties") instanceof Map) {
         resolved.put("additionalProperties", resolveNode((Map<String, Object>) resolved.get("additionalProperties"), defs));
      }

      return resolved;
   }

   @SuppressWarnings("unchecked")
   private Map<String, Object> resolveRef(String refPath, Map<String, Object> defs) {
      if (!refPath.startsWith("#/$defs/")) {
         return null;
      }
      String defName = refPath.substring("#/$defs/".length());
      Object def = defs.get(defName);
      return def instanceof Map ? (Map<String, Object>) def : null;
   }

   @SuppressWarnings("unchecked")
   private Map<String, Object> mergeSchemas(List<Map<String, Object>> schemas, Map<String, Object> defs) {
      Map<String, Object> merged = new LinkedHashMap<>();
      Map<String, Object> mergedProperties = new LinkedHashMap<>();

      for (Map<String, Object> schema : schemas) {
         Map<String, Object> resolvedAlt = resolveNode(new LinkedHashMap<>(schema), defs);
         for (Map.Entry<String, Object> entry : resolvedAlt.entrySet()) {
            if ("properties".equals(entry.getKey())) {
               mergedProperties.putAll((Map<String, Object>) entry.getValue());
            } else {
               merged.put(entry.getKey(), entry.getValue());
            }
         }
      }
      if (!mergedProperties.isEmpty()) {
         merged.put("properties", mergedProperties);
      }
      return merged;
   }
}
