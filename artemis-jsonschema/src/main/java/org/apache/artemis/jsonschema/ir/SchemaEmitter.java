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

import java.util.*;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.artemis.jsonschema.emitters.*;

/**
 * Emits JSON Schema (Draft 7) from an enriched SchemaIR graph.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Traverse IR nodes and emit JSON Schema structures
 *   <li>Resolve $ref vs inline decisions based on usage counts
 *   <li>Apply enrichments during emission
 *   <li>Handle polymorphism (allOf, oneOf patterns)
 * </ul>
 *
 * <p>This class is stateless after construction and can be reused across multiple emissions.
 */
public class SchemaEmitter implements EmissionContext {

   private final SchemaIR ir;
   private final Map<SchemaIR.PropertyType, PropertyEmitter> emitterRegistry =
         new EnumMap<>(SchemaIR.PropertyType.class);

   /**
    * Initializes the emitter with a pre-built IR graph and registers a strategy emitter for each
    * {@link SchemaIR.PropertyType} so property emission is fully table-driven.
    *
    * @param ir enriched intermediate representation to emit from
    */
   public SchemaEmitter(SchemaIR ir) {
      this.ir = ir;
      PrimitivePropertyEmitter primitiveEmitter = new PrimitivePropertyEmitter();
      emitterRegistry.put(SchemaIR.PropertyType.PRIMITIVE, primitiveEmitter);
      emitterRegistry.put(SchemaIR.PropertyType.ENUM, primitiveEmitter);
      emitterRegistry.put(SchemaIR.PropertyType.NESTED_OBJECT, new NestedObjectPropertyEmitter());
      emitterRegistry.put(SchemaIR.PropertyType.MAP_VALUE, new MapValuePropertyEmitter());
      emitterRegistry.put(
            SchemaIR.PropertyType.COLLECTION_ELEMENT, new CollectionElementPropertyEmitter());
      emitterRegistry.put(
            SchemaIR.PropertyType.MAP_COLLECTION_VALUE, new MapCollectionValuePropertyEmitter());
   }

   /**
    * Emit a complete JSON Schema (Draft 7) document from the enriched IR graph.
    *
    * <p>Traverses the IR starting from ConfigurationImpl as root, extracting classes with usageCount
    * &gt; 1 into $defs with $ref pointers. Enrichments and polymorphism (allOf/oneOf) are resolved
    * during emission.
    *
    * @return Complete JSON Schema as a nested Map structure, ready for serialization
    */
   public Map<String, Object> emitSchema() {
      Map<String, Object> schema = new LinkedHashMap<>();
      schema.put("$schema", "http://json-schema.org/draft-07/schema#");
      schema.put("title", "Apache Artemis Broker Configuration");
      schema.put("type", new SchemaType(SchemaType.Kind.OBJECT).toSchemaValue());

      // Phase 1: emit root inline (no $defs, no $ref — everything inlined)
      SchemaIR.ClassNode rootNode = ir.getNode(ConfigurationImpl.class.getName());
      Map<String, Object> rootSchema = emitClassSchema(rootNode, false, Location.root());

      @SuppressWarnings("unchecked")
      Map<String, Object> properties = (Map<String, Object>) rootSchema.get("properties");

      if (properties == null || properties.isEmpty()) {
         throw new IllegalStateException(
               "ConfigurationImpl produced no properties — IR is empty or broken");
      }

      schema.put("properties", properties);

      // Phase 2: content-hash deduplication — extract repeated sub-schemas to $defs
      SchemaDeduplicator deduplicator = new SchemaDeduplicator();
      Map<String, Object> defs = deduplicator.deduplicate(schema);
      if (!defs.isEmpty()) {
         schema.put("$defs", defs);
      }

      return schema;
   }

   /**
    * Emit a class as a flat {@code type: object} with all its properties inlined.
    * No allOf, no $ref — deduplication happens as a post-process step.
    *
    * @param node the IR class node to emit
    * @param isDefEmission unused (kept for interface compatibility)
    * @param location typed path for enrichment lookups
    * @return the emitted JSON Schema fragment
    */
   @Override
   public Map<String, Object> emitClassSchema(
         SchemaIR.ClassNode node, boolean isDefEmission, Location location) {
      Map<String, Object> schema = new LinkedHashMap<>();

      schema.put("type", new SchemaType(SchemaType.Kind.OBJECT).toSchemaValue());

      Map<String, Object> properties = new LinkedHashMap<>();
      for (SchemaIR.PropertyNode prop : node.getProperties().values()) {
         properties.put(prop.getName(), emitPropertySchema(prop, location));
      }
      schema.put("properties", properties);

      if (node.getRequired() != null && !node.getRequired().isEmpty()) {
         schema.put("required", node.getRequired());
      }

      node.getClassMetadata().emitInto(schema);

      return schema;
   }

   /**
    * Dispatches a single property to the strategy emitter registered for its {@link
    * SchemaIR.PropertyType}, keeping this class free of type-specific logic.
    *
    * @param prop the property IR node
    * @param location parent location (the property computes its full location from this)
    * @return the emitted property schema fragment
    */
   private Map<String, Object> emitPropertySchema(SchemaIR.PropertyNode prop, Location location) {
      if (prop == null) {
         throw new IllegalArgumentException("PropertyNode must not be null for location: " + location);
      }
      PropertyEmitter emitter = emitterRegistry.get(prop.getPropertyType());
      if (emitter == null) {
         throw new IllegalStateException(
               "No emitter registered for property type: " + prop.getPropertyType());
      }
      return emitter.emit(prop, ir, location.child(prop), this);
   }

}
