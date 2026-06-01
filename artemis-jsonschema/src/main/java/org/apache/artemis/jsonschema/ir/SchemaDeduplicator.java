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

package org.apache.artemis.jsonschema.ir;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-emission deduplication pass: walks the emitted JSON Schema tree, hashes each
 * {@code type: object} sub-schema, and extracts duplicates to {@code $defs} with
 * {@code $ref} pointers replacing the inline copies.
 *
 * <p>This replaces the heuristic-based {@code shouldExtract} approach with content-addressed
 * deduplication — structurally identical sub-schemas share a single definition regardless
 * of how they were discovered or enriched.
 */
public class SchemaDeduplicator {

   private static final Logger LOG = LoggerFactory.getLogger(SchemaDeduplicator.class);

   /**
    * Deduplicate the emitted schema in-place. Extracts repeated sub-schemas to {@code $defs}
    * and replaces inline occurrences with {@code $ref}.
    *
    * @param schema the complete emitted schema (mutated in-place)
    * @return the {@code $defs} map to attach to the schema (may be empty)
    */
   @SuppressWarnings("unchecked")
   public Map<String, Object> deduplicate(Map<String, Object> schema) {
      Map<String, Object> defs = new LinkedHashMap<>();
      Set<String> usedNames = new HashSet<>();
      int totalSites = 0;

      // Iterate until no more duplicates found — each pass collapses the deepest
      // duplicates, exposing new duplicates at higher levels.
      while (true) {
         Map<String, List<SchemaLocation>> hashToLocations = new LinkedHashMap<>();
         Map<String, Map<String, Object>> hashToSchema = new LinkedHashMap<>();

         walkAndHash(schema, null, null, hashToLocations, hashToSchema, new HashSet<>());

         int extracted = 0;
         for (Map.Entry<String, List<SchemaLocation>> entry : hashToLocations.entrySet()) {
            List<SchemaLocation> locations = entry.getValue();
            if (locations.size() < 2) {
               continue;
            }

            String hash = entry.getKey();
            Map<String, Object> subSchema = hashToSchema.get(hash);
            String name = deriveName(subSchema, usedNames);

            defs.put(name, subSchema);

            for (SchemaLocation loc : locations) {
               Map<String, Object> ref = new LinkedHashMap<>();
               ref.put("$ref", "#/$defs/" + name);
               Map<String, Object> original = loc.get();
               if (original != null) {
                  for (Map.Entry<String, Object> meta : original.entrySet()) {
                     if (meta.getKey().startsWith("x-") && !"x-java-class".equals(meta.getKey())) {
                        ref.put(meta.getKey(), meta.getValue());
                     }
                  }
               }
               loc.replace(ref);
            }
            extracted++;
            totalSites += locations.size();
         }

         if (extracted == 0) {
            break;
         }
      }

      LOG.info("Deduplication: {} $defs extracted from {} duplicate sites", defs.size(), totalSites);
      return defs;
   }

   /**
    * Recursively walk the schema, hashing each {@code type: object} node that has
    * {@code properties} (class-like sub-schemas). Records parent map + key for later replacement.
    */
   @SuppressWarnings("unchecked")
   private void walkAndHash(Object current, Map<String, Object> parent, String parentKey,
         Map<String, List<SchemaLocation>> hashToLocations,
         Map<String, Map<String, Object>> hashToSchema,
         Set<Integer> visiting) {

      if (current instanceof Map) {
         Map<String, Object> map = (Map<String, Object>) current;

         // Cycle guard on identity
         int id = System.identityHashCode(map);
         if (visiting.contains(id)) {
            return;
         }
         visiting.add(id);

         // Is this a class-like sub-schema worth deduplicating?
         if (isClassLikeSchema(map) && parent != null) {
            String hash = computeHash(map);
            hashToLocations.computeIfAbsent(hash, k -> new ArrayList<>())
                  .add(new SchemaLocation(parent, parentKey));
            hashToSchema.putIfAbsent(hash, map);
         }

         // Recurse into all values
         for (Map.Entry<String, Object> entry : new ArrayList<>(map.entrySet())) {
            Object value = entry.getValue();
            if (value instanceof Map) {
               walkAndHash(value, map, entry.getKey(), hashToLocations, hashToSchema, visiting);
            } else if (value instanceof List) {
               walkAndHash(value, map, entry.getKey(), hashToLocations, hashToSchema, visiting);
            }
         }

         visiting.remove(id);

      } else if (current instanceof List) {
         List<Object> list = (List<Object>) current;
         for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Map) {
               // For list items, we need a way to reference them for replacement
               // Use a wrapper approach: store the list + index
               Map<String, Object> itemMap = (Map<String, Object>) item;
               int itemId = System.identityHashCode(itemMap);
               if (!visiting.contains(itemId)) {
                  visiting.add(itemId);
                  if (isClassLikeSchema(itemMap)) {
                     String hash = computeHash(itemMap);
                     hashToLocations.computeIfAbsent(hash, k -> new ArrayList<>())
                           .add(new SchemaLocation(list, i));
                     hashToSchema.putIfAbsent(hash, itemMap);
                  }
                  // Recurse into list item
                  for (Map.Entry<String, Object> entry : new ArrayList<>(itemMap.entrySet())) {
                     Object value = entry.getValue();
                     if (value instanceof Map || value instanceof List) {
                        walkAndHash(value, itemMap, entry.getKey(), hashToLocations, hashToSchema, visiting);
                     }
                  }
                  visiting.remove(itemId);
               }
            }
         }
      }
   }

   /**
    * A sub-schema is "class-like" if it has {@code type: object} and {@code properties}
    * with at least 2 named properties. Single-property objects aren't worth extracting.
    */
   @SuppressWarnings("unchecked")
   private boolean isClassLikeSchema(Map<String, Object> map) {
      Object type = map.get("type");
      if (!"object".equals(type)) {
         return false;
      }
      Object props = map.get("properties");
      if (!(props instanceof Map)) {
         return false;
      }
      return ((Map<?, ?>) props).size() >= 2;
   }

   /**
    * Compute a canonical content hash of a sub-schema. Keys are sorted recursively
    * to ensure order-independent equality.
    */
   private String computeHash(Map<String, Object> map) {
      String canonical = canonicalize(map);
      try {
         MessageDigest md = MessageDigest.getInstance("SHA-256");
         byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
         StringBuilder sb = new StringBuilder();
         for (int i = 0; i < 8; i++) { // first 8 bytes = 16 hex chars
            sb.append(String.format("%02x", digest[i]));
         }
         return sb.toString();
      } catch (NoSuchAlgorithmException e) {
         return String.valueOf(canonical.hashCode());
      }
   }

   /**
    * Produce a canonical string representation of a schema node (sorted keys, recursive).
    * Used for hash computation. Excludes {@code x-java-class} to allow differently-named
    * classes with identical structure to be deduplicated.
    */
   @SuppressWarnings("unchecked")
   private String canonicalize(Object obj) {
      if (obj instanceof Map) {
         Map<String, Object> map = (Map<String, Object>) obj;
         StringBuilder sb = new StringBuilder("{");
         map.entrySet().stream()
               .filter(e -> !"x-java-class".equals(e.getKey()))
               .sorted(Map.Entry.comparingByKey())
               .forEach(e -> sb.append(e.getKey()).append(':').append(canonicalize(e.getValue())).append(','));
         sb.append('}');
         return sb.toString();
      } else if (obj instanceof List) {
         List<?> list = (List<?>) obj;
         StringBuilder sb = new StringBuilder("[");
         for (Object item : list) {
            sb.append(canonicalize(item)).append(',');
         }
         sb.append(']');
         return sb.toString();
      } else if (obj == null) {
         return "null";
      } else {
         return obj.toString();
      }
   }

   /**
    * Derive a $defs name from the sub-schema. Uses {@code x-java-class} simple name
    * if available, otherwise generates from content.
    */
   private String deriveName(Map<String, Object> subSchema, Set<String> usedNames) {
      String javaClass = (String) subSchema.get("x-java-class");
      String name;
      if (javaClass != null) {
         int lastDot = javaClass.lastIndexOf('.');
         name = lastDot >= 0 ? javaClass.substring(lastDot + 1) : javaClass;
         name = name.replace('$', '_');
      } else {
         String desc = (String) subSchema.get("description");
         if (desc != null && desc.startsWith("Transport-specific parameters")) {
            name = "TransportParams";
         } else if (desc != null && desc.startsWith("Configuration for ")) {
            name = desc.substring("Configuration for ".length()).replaceAll("\\s+", "");
         } else {
            name = "Schema_" + usedNames.size();
         }
      }

      // Disambiguate collisions
      String baseName = name;
      int counter = 2;
      while (usedNames.contains(name)) {
         name = baseName + "_" + counter++;
      }
      usedNames.add(name);
      return name;
   }

   /**
    * Location reference for a sub-schema within the tree — either a map entry or a list element.
    */
   private static class SchemaLocation {
      final Object container;
      final Object key;

      SchemaLocation(Map<String, Object> parent, String key) {
         this.container = parent;
         this.key = key;
      }

      SchemaLocation(List<Object> parent, int index) {
         this.container = parent;
         this.key = index;
      }

      @SuppressWarnings("unchecked")
      void replace(Map<String, Object> ref) {
         if (container instanceof Map) {
            ((Map<String, Object>) container).put((String) key, ref);
         } else if (container instanceof List) {
            ((List<Object>) container).set((Integer) key, ref);
         }
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> get() {
         if (container instanceof Map) {
            return (Map<String, Object>) ((Map<String, Object>) container).get(key);
         } else if (container instanceof List) {
            return (Map<String, Object>) ((List<Object>) container).get((Integer) key);
         }
         return null;
      }
   }
}
