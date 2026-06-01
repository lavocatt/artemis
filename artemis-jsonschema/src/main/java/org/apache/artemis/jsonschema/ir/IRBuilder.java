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

import org.apache.activemq.artemis.api.config.annotation.ByteNotation;
import org.apache.activemq.artemis.api.config.annotation.ConfigMap;
import org.apache.activemq.artemis.api.config.annotation.ConfigProperty;
import org.apache.activemq.artemis.api.config.annotation.Discriminator;
import org.apache.activemq.artemis.api.config.annotation.FactoryParams;
import org.apache.activemq.artemis.api.config.annotation.FactoryType;
import org.apache.activemq.artemis.api.config.annotation.InitKeys;
import org.apache.activemq.artemis.api.config.annotation.InternalOpaqueProperty;
import org.apache.activemq.artemis.api.config.annotation.MapKeys;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.apache.artemis.jsonschema.config.SchemaGeneratorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.lang.reflect.*;
import java.util.*;

/**
 * IR-based JSON Schema generator using two-phase architecture.
 *
 * <p>Architecture:
 * <ol>
 *   <li>Build IR graph from reflection (track all class usage)</li>
 *   <li>Enrichers mutate IR nodes (XSD, JavaDoc, etc.)</li>
 *   <li>Analyze graph to identify $def candidates (usageCount > 1)</li>
 *   <li>Emit JSON schema with proper $ref usage</li>
 * </ol>
 *
 * <p>This prevents invalid schemas and enables K8s CRD compatibility (&lt;1MB).
 */
public class IRBuilder {

      private static final Logger LOG = LoggerFactory.getLogger(IRBuilder.class);

      private static final Set<String> IGNORED_PROPERTIES = new HashSet<>(
            SchemaGeneratorConfig.load().getIgnoredProperties()
      );

      private Configuration configInstance;
      private final SchemaIR ir = new SchemaIR();
      private final Set<String> processingClasses = new HashSet<>();
      private PolymorphismResolver polymorphismResolver;

      public IRBuilder() {
      }

      /**
       * Generate the IR graph by recursively reflecting over ConfigurationImpl and all reachable types.
       *
       * <p>Populates class nodes, property nodes, usage counts, and factory variant structures.
       * The returned IR is mutable and intended to be enriched by subsequent extractor passes
       * before being handed to {@link SchemaEmitter#emitSchema(SchemaIR)}.
       *
       * @return Populated IR graph ready for enrichment and emission
       * @throws Exception if ConfigurationImpl cannot be introspected (e.g., missing on classpath)
       */
      public void generateIR() throws Exception {
            configInstance = getConfigInstance();
            polymorphismResolver = new PolymorphismResolver(ir);

            buildClassIR(ConfigurationImpl.class, Location.root(), configInstance);

            applyDiscriminatorConsts();
            applyInitKeys();
            applyFactoryVariants();
      }

      /**
       * Post-pass: for each class with @Discriminator, set const values on subclass
       * discriminator fields. Must run after all classes are built in the IR.
       */
      private void applyDiscriminatorConsts() {
            for (SchemaIR.ClassNode node : ir.getAllNodes()) {
                  try {
                        Class<?> clazz = Class.forName(node.getClassName());
                        Discriminator disc = clazz.getAnnotation(Discriminator.class);
                        if (disc == null) continue;

                        String field = disc.field();
                        for (Discriminator.Mapping mapping : disc.value()) {
                              String subClassName = mapping.subtype().getName();
                              String constValue = mapping.name();

                              SchemaIR.ClassNode subNode = ir.getOrCreateNode(subClassName);
                              SchemaIR.PropertyNode propNode = subNode.getProperties().get(field);
                              if (propNode != null) {
                                    propNode.getSchema().remove("enum");
                                    propNode.getSchema().put("const", constValue);
                              }
                        }
                  } catch (ClassNotFoundException e) {
                        // skip — class not on classpath
                  }
            }
      }

      private Map<String, Object> scanConstantsForMapKeys(Class<?> constantsClass) {
            Map<String, Object> knownProperties = new LinkedHashMap<>();
            Map<String, Object> defaults = new LinkedHashMap<>();

            // Extract DEFAULT_* fields for type inference
            for (java.lang.reflect.Field field : constantsClass.getDeclaredFields()) {
                  int mods = field.getModifiers();
                  if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods) || !Modifier.isFinal(mods)) continue;
                  String name = field.getName();
                  if (!name.startsWith("DEFAULT_")) continue;
                  try {
                        defaults.put(name.substring("DEFAULT_".length()), field.get(null));
                  } catch (IllegalAccessException e) { /* skip */ }
            }

            // Extract String constants as keys
            for (java.lang.reflect.Field field : constantsClass.getDeclaredFields()) {
                  int mods = field.getModifiers();
                  if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods) || !Modifier.isFinal(mods)) continue;
                  if (field.getType() != String.class) continue;
                  try {
                        String value = (String) field.get(null);
                        if (value == null || value.isEmpty()) continue;
                        if (value.contains("_") && value.equals(value.toUpperCase())) continue;
                        if (value.contains("$") || value.contains("/")) continue;

                        Object defaultValue = defaults.get(field.getName());
                        Map<String, Object> propSchema = new LinkedHashMap<>();
                        if (defaultValue instanceof Boolean) {
                              propSchema.put("type", "boolean");
                        } else if (defaultValue instanceof Number) {
                              propSchema.put("type", "integer");
                        } else {
                              propSchema.put("type", "string");
                        }
                        knownProperties.put(value, propSchema);
                  } catch (IllegalAccessException e) { /* skip */ }
            }
            return knownProperties;
      }

      /**
       * Post-pass: scan all IR classes for @InitKeys on the Java class. For each annotated plugin,
       * find the "init" property in the IR node and enrich it with typed properties.
       */
      private void applyInitKeys() {
            for (SchemaIR.ClassNode node : ir.getAllNodes()) {
                  try {
                        Class<?> clazz = Class.forName(node.getClassName());
                        InitKeys ik = clazz.getAnnotation(InitKeys.class);
                        if (ik == null) continue;

                        Map<String, Object> knownProperties = new LinkedHashMap<>();
                        for (InitKeys.Key key : ik.value()) {
                              Map<String, Object> propSchema = new LinkedHashMap<>();
                              propSchema.put("type", key.type());
                              knownProperties.put(key.name(), propSchema);
                        }

                        if (knownProperties.isEmpty()) continue;

                        String simpleName = clazz.getSimpleName() + "InitProperties";

                        // The "init" property is on NamedPropertyConfiguration (the class that
                        // brokerPlugins map values resolve to). Find the init property on this
                        // node or parent nodes and enrich at all paths where this class appears.
                        SchemaIR.PropertyNode initProp = node.getProperties().get("init");
                        if (initProp != null) {
                              // Direct enrichment on the init property's schema
                              Map<String, Object> initSchema = initProp.getSchema();
                              initSchema.put("properties", knownProperties);
                              initSchema.put("additionalProperties", false);
                              initSchema.put("x-java-class", node.getClassName() + "." + simpleName);
                        }
                  } catch (ClassNotFoundException e) {
                        // skip
                  }
            }
      }

      /**
       * Post-pass: find all classes with @FactoryParams, read their constants and @FactoryType,
       * then create synthetic variant ClassNodes for TransportConfiguration and
       * JaasAppConfigurationEntry property nodes.
       */
      private void applyFactoryVariants() {
            Reflections reflections = new Reflections("org.apache.activemq.artemis", Scanners.TypesAnnotated);
            Set<Class<?>> factoryClasses = reflections.getTypesAnnotatedWith(FactoryParams.class);

            // Group by target class (Transport vs JAAS)
            Map<String, List<FactoryInfo>> factoriesByTarget = new LinkedHashMap<>();
            // Transport factories target TransportConfiguration
            String transportTarget = "org.apache.activemq.artemis.api.core.TransportConfiguration";
            // JAAS factories target JaasAppConfigurationEntry
            String jaasTarget = "org.apache.activemq.artemis.core.config.JaasAppConfigurationEntry";

            for (Class<?> factoryClass : factoryClasses) {
                  FactoryParams fp = factoryClass.getAnnotation(FactoryParams.class);
                  FactoryType ft = factoryClass.getAnnotation(FactoryType.class);

                  List<String> params = scanFactoryParams(fp.constantsClass());
                  FactoryType.Kind kind = ft != null ? ft.value() : FactoryType.Kind.BOTH;

                  // Determine target: if it implements AcceptorFactory/ConnectorFactory → transport
                  // If it implements LoginModule → JAAS
                  String target = isTransportFactory(factoryClass) ? transportTarget : jaasTarget;

                  factoriesByTarget.computeIfAbsent(target, k -> new ArrayList<>())
                        .add(new FactoryInfo(factoryClass.getName(), params, kind));
            }

            // Build variants for each target class
            for (Map.Entry<String, List<FactoryInfo>> entry : factoriesByTarget.entrySet()) {
                  String targetClass = entry.getKey();
                  List<FactoryInfo> factories = entry.getValue();
                  String discriminatorField = targetClass.contains("Transport") ? "factoryClassName" : "loginModuleClass";
                  String paramsField = "params";

                  ir.markAsFactoryBase(targetClass);
                  SchemaIR.ClassNode baseNode = ir.getOrCreateNode(targetClass);

                  for (SchemaIR.ClassNode classNode : List.copyOf(ir.getAllNodes())) {
                        for (SchemaIR.PropertyNode propNode : classNode.getProperties().values()) {
                              if (!targetClass.equals(propNode.getTargetClassName())) continue;
                              Location location = propNode.getLocation();
                              if (location == null) continue;

                              String propertyName = location.leafName();
                              for (FactoryInfo fi : factories) {
                                    if (shouldIncludeFactory(fi, propertyName)) {
                                          propNode.addFactoryVariant(fi.className, fi.params);
                                          createVariantNode(fi.className, fi.params, baseNode,
                                                location.wildcard(), discriminatorField, paramsField);
                                    }
                              }
                        }
                  }
            }
      }

      private boolean isTransportFactory(Class<?> clazz) {
            for (Class<?> iface : clazz.getInterfaces()) {
                  String name = iface.getSimpleName();
                  if (name.contains("AcceptorFactory") || name.contains("ConnectorFactory")) return true;
            }
            return false;
      }

      private boolean shouldIncludeFactory(FactoryInfo fi, String propertyName) {
            if (fi.kind == FactoryType.Kind.BOTH) return true;
            String lower = propertyName.toLowerCase();
            if (fi.kind == FactoryType.Kind.ACCEPTOR) {
                  return !lower.contains("connector") || lower.contains("acceptor");
            }
            if (fi.kind == FactoryType.Kind.CONNECTOR) {
                  return !lower.contains("acceptor") || lower.contains("connector");
            }
            return true;
      }

      private List<String> scanFactoryParams(Class<?> constantsClass) {
            List<String> params = new ArrayList<>();
            // Pattern 1: *_PROP_NAME constants
            for (java.lang.reflect.Field field : constantsClass.getDeclaredFields()) {
                  int mods = field.getModifiers();
                  if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods) || !Modifier.isFinal(mods)) continue;
                  if (field.getType() != String.class) continue;
                  String name = field.getName();
                  if (name.endsWith("_PROP_NAME") || name.endsWith("_PROP") || name.endsWith("_PROPERTY")
                        || name.endsWith("_PARAM_NAME") || name.endsWith("_PARAM")) {
                        try {
                              String value = (String) field.get(null);
                              if (value != null && !value.isEmpty()) {
                                    // Qualified values: take the short form after last dot
                                    int lastDot = value.lastIndexOf('.');
                                    params.add(lastDot >= 0 ? value.substring(lastDot + 1) : value);
                              }
                        } catch (IllegalAccessException e) { /* skip */ }
                  }
            }

            // Pattern 2: inner enums ConfigKey, ParamKey, or *Key
            for (Class<?> innerClass : constantsClass.getDeclaredClasses()) {
                  if (!innerClass.isEnum()) continue;
                  String innerName = innerClass.getSimpleName();
                  if (innerName.endsWith("Key") || innerName.equals("ConfigKey") || innerName.equals("ParamKey")) {
                        for (Object enumConstant : innerClass.getEnumConstants()) {
                              try {
                                    Method getName = innerClass.getMethod("getName");
                                    params.add((String) getName.invoke(enumConstant));
                              } catch (NoSuchMethodException e) {
                                    // Fallback: use enum name, convert SCREAMING_SNAKE to camelCase
                                    String raw = ((Enum<?>) enumConstant).name();
                                    params.add(screaming2camel(raw));
                              } catch (Exception e) { /* skip */ }
                        }
                  }
            }

            return params;
      }

      private static String screaming2camel(String screaming) {
            String[] parts = screaming.toLowerCase().split("_");
            StringBuilder sb = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                  sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
            return sb.toString();
      }

      private void createVariantNode(String factoryClassName, List<String> factoryParams,
                                     SchemaIR.ClassNode baseNode, Location contextLocation,
                                     String discriminatorField, String paramsField) {
            String simpleName = factoryClassName.substring(factoryClassName.lastIndexOf('.') + 1);
            SchemaIR.ClassNode variantNode = ir.getOrCreateNode(simpleName);

            variantNode.getClassMetadata().setJavaClass(factoryClassName);
            variantNode.getClassMetadata().setDescription("Configuration for " + simpleName);
            variantNode.getClassMetadata().setFactoryVariant(true);
            variantNode.getClassMetadata().setContextPath(contextLocation.toDotted());

            for (Map.Entry<String, SchemaIR.PropertyNode> baseProp : baseNode.getProperties().entrySet()) {
                  String propName = baseProp.getKey();
                  SchemaIR.PropertyNode basePropNode = baseProp.getValue();
                  SchemaIR.PropertyNode variantProp = variantNode.getOrCreateProperty(propName);

                  if (propName.equals(discriminatorField)) {
                        variantProp.setSchemaField("const", factoryClassName);
                        variantProp.setSchemaField("default", factoryClassName);
                        variantProp.setSchemaType(new SchemaType(SchemaType.Kind.STRING));
                        variantProp.setSchemaField("x-access", "RW");
                        variantNode.setRequired(List.of(discriminatorField));
                  } else if (propName.equals(paramsField)) {
                        for (Map.Entry<String, Object> schemaEntry : basePropNode.getSchema().entrySet()) {
                              variantProp.setSchemaField(schemaEntry.getKey(), schemaEntry.getValue());
                        }
                        variantProp.setPropertyType(SchemaIR.PropertyType.PRIMITIVE);
                        Map<String, Object> paramProperties = new LinkedHashMap<>();
                        for (String paramName : factoryParams) {
                              Map<String, Object> paramSchema = new LinkedHashMap<>();
                              paramSchema.put("type", new SchemaType(SchemaType.Kind.STRING).toSchemaValue());
                              paramSchema.put("x-access", "RW");
                              paramProperties.put(paramName, paramSchema);
                        }
                        variantProp.setSchemaField("properties", paramProperties);
                        variantProp.setSchemaField("additionalProperties", false);
                  } else {
                        for (Map.Entry<String, Object> schemaEntry : basePropNode.getSchema().entrySet()) {
                              variantProp.setSchemaField(schemaEntry.getKey(), schemaEntry.getValue());
                        }
                        if (basePropNode.getTargetClassName() != null) {
                              variantProp.setTargetClassName(basePropNode.getTargetClassName());
                        }
                        variantProp.setPropertyType(basePropNode.getPropertyType());
                  }
            }
      }

      private static class FactoryInfo {
            final String className;
            final List<String> params;
            final FactoryType.Kind kind;

            FactoryInfo(String className, List<String> params, FactoryType.Kind kind) {
                  this.className = className;
                  this.params = params;
                  this.kind = kind;
            }
      }

      /**
       * @return the populated IR graph (only valid after generateIR() has been called)
       */
      public SchemaIR getIR() {
            return ir;
      }

      /**
       * Log summary statistics about the generated IR graph.
       */
      public void logStats() {
            LOG.info("Generated IR: {} classes tracked", ir.getAllNodes().size());
      }

      /**
       * Log documentation coverage: how many root properties have descriptions after enrichment.
       */
      public void logDocumentationCoverage() {
            SchemaIR.ClassNode rootNode = ir.getOrCreateNode(ConfigurationImpl.class.getName());

            int totalProps = 0;
            int documentedProps = 0;
            List<String> undocumented = new ArrayList<>();

            for (SchemaIR.PropertyNode prop : rootNode.getProperties().values()) {
                  totalProps++;
                  Location propLocation = Location.root().child(prop);
                  Map<String, Object> enrichment = ir.getEnrichment(propLocation);
                  if (enrichment.containsKey("description")) {
                        documentedProps++;
                  } else {
                        undocumented.add(prop.getName());
                  }
            }

            double coverage = totalProps > 0 ? (100.0 * documentedProps / totalProps) : 0;
            LOG.info("Documentation coverage: {}/{} root properties ({} %)",
                  documentedProps, totalProps, String.format("%.1f", coverage));

            if (!undocumented.isEmpty() && LOG.isDebugEnabled()) {
                  LOG.debug("Undocumented root properties: {}", undocumented);
            }
      }

      /**
       * Log how many classes qualify for $defs extraction (determined during IR generation).
       */
      public void logExtractionStats() {
            LOG.info("IR contains {} classes (deduplication handles $defs extraction)", ir.getAllNodes().size());
      }


      /**
       * Recursively build IR nodes for a class and all its bean properties.
       * Handles circular references via the processingClasses guard set.
       *
       * @param clazz the class to introspect (null is a no-op)
       * @param location typed property path from root
       * @param instance a live instance of clazz used to extract default values via getter invocation,
       *                 or null if no defaults can be extracted (e.g., nested objects without a parent instance)
       */
      private void buildClassIR(Class<?> clazz, Location location, Object instance) throws Exception {
            if (clazz == null) {
                  return;
            }
            String className = clazz.getName();

            // Record usage FIRST (even for circular refs, we want to track all usage contexts)

            // Circular reference check - if already processing, don't recurse
            if (processingClasses.contains(className)) {
                  return;
            }

            processingClasses.add(className);

            SchemaIR.ClassNode classNode = ir.getOrCreateNode(className);

            // Store class-level metadata
            if (!className.startsWith("java.")) {
                  classNode.getClassMetadata().setJavaClass(className);
            }

            // Introspect properties
            BeanInfo beanInfo = Introspector.getBeanInfo(clazz);
            java.beans.PropertyDescriptor[] descriptors = beanInfo.getPropertyDescriptors();
            Arrays.sort(descriptors, Comparator.comparing(java.beans.PropertyDescriptor::getName));

            for (java.beans.PropertyDescriptor pd : descriptors) {
                  String propName = pd.getName();

                  if (IGNORED_PROPERTIES.contains(propName)) {
                        continue;
                  }

                  try {
                        buildPropertyIR(pd, location, instance, classNode);
                  } catch (Exception e) {
                        // Record failure but continue
                        SchemaIR.PropertyNode propNode = classNode.getOrCreateProperty(propName);
                        propNode.setSchemaField("type", new SchemaType(SchemaType.Kind.STRING).toSchemaValue()); // fallback for unknown types
                        propNode.setSchemaField("x-exploration-status", "reflection-failed");
                        propNode.setSchemaField("x-exploration-reason", e.getMessage());
                  }
            }

            processingClasses.remove(className);
      }

      /**
       * Build an IR property node from a single JavaBean property descriptor.
       * Determines the property's JSON Schema type category (primitive, enum, map,
       * collection, or nested object) and delegates to the appropriate handler.
       * Also extracts access mode, default values via getter invocation, and
       * {@link ConfigProperty} annotation metadata.
       *
       * @param pd              the bean property descriptor; properties with a null type are skipped
       * @param parentLocation  typed path of the owning class
       * @param parentInstance  live instance of the parent class used to invoke the getter
       *                        for default value extraction, or null if unavailable
       * @param classNode       the IR class node to attach the resulting property node to
       * @throws Exception if recursive introspection of a nested/collection/map value type fails
       */
      private void buildPropertyIR(java.beans.PropertyDescriptor pd, Location parentLocation, Object parentInstance, SchemaIR.ClassNode classNode)
                  throws Exception {

            String propName = pd.getName();
            Location fullLocation = parentLocation.child(propName);
            String fullPath = fullLocation.toDotted();
            Class<?> propType = pd.getPropertyType();
            Method getter = pd.getReadMethod();
            // java.beans.Introspector misses fluent setters (non-void return type).
            // Fall back to scanning the declaring class for a set<Name> method directly.
            Method setter = pd.getWriteMethod() != null ? pd.getWriteMethod()
                  : findFluentSetter(pd.getName(), propType, getter);

            if (propType == null) {
                  return;
            }

            SchemaIR.PropertyNode propNode = classNode.getOrCreateProperty(propName);

            String access;
            if (getter != null && setter != null) {
                  access = "RW";
            } else if (getter != null) {
                  access = "RO";
            } else if (setter != null) {
                  access = "WO";
            } else {
                  access = "UNKNOWN";
                  LOG.error("Property {} has neither getter nor setter — this should not happen", fullPath);
            }
            propNode.setSchemaField("x-access", access);

            // Process @ConfigProperty annotation if present (highest-priority metadata)
            applyConfigPropertyAnnotation(getter, setter, propNode);

            // Detect Java @Deprecated on getter or setter
            if ((getter != null && getter.isAnnotationPresent(Deprecated.class))
                  || (setter != null && setter.isAnnotationPresent(Deprecated.class))) {
                  propNode.setSchemaField("x-deprecated", true);
            }

            // Check @InternalOpaqueProperty — skip with warning if present
            InternalOpaqueProperty opaqueAnnotation = getter != null ? getter.getAnnotation(InternalOpaqueProperty.class) : null;
            if (opaqueAnnotation == null && setter != null) {
                  opaqueAnnotation = setter.getAnnotation(InternalOpaqueProperty.class);
            }
            if (opaqueAnnotation != null) {
                  LOG.warn("{}.{} excluded from schema (@InternalOpaqueProperty: \"{}\")",
                        parentLocation.toDotted(), propName, opaqueAnnotation.reason());
                  classNode.getProperties().remove(propName);
                  return;
            }

            // Check @ByteNotation — emit [integer, string] union type
            boolean hasByteNotation = (getter != null && getter.isAnnotationPresent(ByteNotation.class))
                  || (setter != null && setter.isAnnotationPresent(ByteNotation.class));

            // Handle different property types
            if (propType.isPrimitive() || isWrapperType(propType) || isStringLikeType(propType)) {
                  if (hasByteNotation) {
                        propNode.setPropertyType(SchemaIR.PropertyType.PRIMITIVE);
                        propNode.setSchemaField("type", SchemaType.of(SchemaType.Kind.INTEGER, SchemaType.Kind.STRING).toSchemaValue());
                        propNode.setSchemaField("pattern", "^\\d+\\s*([KkMmGg][Ii]?[Bb]?)?$");
                        propNode.setSchemaField("x-converter", "ByteUtil.convertTextBytes");
                  } else {
                        handlePrimitiveIR(propType, null, propNode);
                  }
            } else if (propType.isEnum()) {
                  handleEnumIR(propType, null, propNode);
            } else if (Map.class.isAssignableFrom(propType)) {
                  handleMapIR(getter, setter, fullLocation, propNode);
            } else if (Collection.class.isAssignableFrom(propType)) {
                  handleCollectionIR(getter, setter, fullLocation, propNode);
            } else if (propType.isArray()) {
                  propNode.setSchemaField("type", new SchemaType(SchemaType.Kind.ARRAY).toSchemaValue());
                  Class<?> componentType = propType.getComponentType();
                  Map<String, Object> items = new LinkedHashMap<>();
                  if (componentType == String.class || isStringLikeType(componentType)) {
                        items.put("type", new SchemaType(SchemaType.Kind.STRING).toSchemaValue());
                  } else if (componentType.isPrimitive() || isWrapperType(componentType)) {
                        items.put("type", new SchemaType(SchemaType.Kind.INTEGER).toSchemaValue());
                  } else {
                        items.put("type", new SchemaType(SchemaType.Kind.STRING).toSchemaValue());
                  }
                  // Check @ConfigProperty.enumValues for array item constraints
                  ConfigProperty cpAnnotation = getter != null ? getter.getAnnotation(ConfigProperty.class) : null;
                  if (cpAnnotation != null && cpAnnotation.enumValues().length > 0) {
                        items.put("enum", List.of(cpAnnotation.enumValues()));
                  }
                  propNode.setSchemaField("items", items);
            } else {
                  // Nested object
                  propNode.setTargetClassName(propType.getName());
                  propNode.setPropertyType(SchemaIR.PropertyType.NESTED_OBJECT);

                  // Get nested instance for recursive introspection (type discovery only)
                  Object nestedInstance = null;
                  if (parentInstance != null && getter != null) {
                        try {
                              nestedInstance = getter.invoke(parentInstance);
                        } catch (Exception e) {
                              LOG.trace("Could not get nested instance for {}: {}", fullPath, e.getMessage());
                        }
                  }
                  buildClassIR(propType, fullLocation, nestedInstance);
            }
      }

      /**
       * Map a Java class to its JSON Schema type and store it on the property node.
       *
       * @param javaType     the Java class from reflection (primitive, wrapper, or string-like)
       * @param defaultValue unused, retained for interface compatibility
       * @param propNode     target property node whose schema fields will be populated
       */
      private void handlePrimitiveIR(Class<?> javaType, Object defaultValue, SchemaIR.PropertyNode propNode) {
            propNode.setPropertyType(SchemaIR.PropertyType.PRIMITIVE);
            propNode.setSchemaField("type", javaTypeToSchemaType(javaType).toSchemaValue());
      }

      private static SchemaType javaTypeToSchemaType(Class<?> javaType) {
            if (javaType == boolean.class || javaType == Boolean.class) {
                  return new SchemaType(SchemaType.Kind.BOOLEAN);
            }
            if (javaType == int.class || javaType == Integer.class ||
                  javaType == long.class || javaType == Long.class ||
                  javaType == short.class || javaType == Short.class ||
                  javaType == byte.class || javaType == Byte.class) {
                  return new SchemaType(SchemaType.Kind.INTEGER);
            }
            if (javaType == double.class || javaType == Double.class ||
                  javaType == float.class || javaType == Float.class) {
                  return new SchemaType(SchemaType.Kind.NUMBER);
            }
            return new SchemaType(SchemaType.Kind.STRING);
      }

      /**
       * Map a Java enum type to a JSON Schema string with an {@code enum} constraint
       * listing every declared constant.
       *
       * @param enumType     the enum class; its constants are serialised via {@code toString()}
       * @param defaultValue unused, retained for interface compatibility
       * @param propNode     target property node whose schema fields will be populated
       */
      private void handleEnumIR(Class<?> enumType, Object defaultValue, SchemaIR.PropertyNode propNode) {
            propNode.setPropertyType(SchemaIR.PropertyType.ENUM);
            propNode.setSchemaField("type", new SchemaType(SchemaType.Kind.STRING).toSchemaValue());

            Object[] constants = enumType.getEnumConstants();
            if (constants != null) {
                  List<String> enumValues = new ArrayList<>();
                  for (Object constant : constants) {
                        enumValues.add(constant.toString());
                  }
                  propNode.setSchemaField("enum", enumValues);
            } else {
                  LOG.error("Class {} is marked as enum but has no constants", enumType.getName());
            }

      }

      /**
       * Build IR for a {@code Map}-typed property. The map key is always assumed to
       * be {@code String}. The value type determines the schema shape:
       * <ul>
       *   <li>Primitive/enum/Object values &rarr; {@code additionalProperties} with
       *       the corresponding inline schema</li>
       *   <li>Complex object values &rarr; a target class reference plus recursive
       *       introspection, with factory-variant detection for polymorphic maps
       *       (e.g., acceptors/connectors)</li>
       * </ul>
       *
       * @param getter   read method for the property, used to resolve generic type args;
       *                 may be null if the property is write-only
       * @param setter   write method, used as fallback for generic type resolution;
       *                 may be null if the property is read-only
       * @param fullLocation typed path of this property from the config root
       * @param propNode target property node whose schema fields will be populated
       * @throws Exception if recursive introspection of a complex value type fails
       */
      private void handleMapIR(Method getter, Method setter, Location fullLocation, SchemaIR.PropertyNode propNode)
                  throws Exception {
            propNode.setSchemaField("type", new SchemaType(SchemaType.Kind.OBJECT).toSchemaValue());

            Type genericType = getter != null ? getter.getGenericReturnType() :
                                       (setter != null ? setter.getGenericParameterTypes()[0] : null);

            if (genericType instanceof ParameterizedType) {
                  ParameterizedType paramType = (ParameterizedType) genericType;
                  Type[] typeArgs = paramType.getActualTypeArguments();

                  if (typeArgs.length < 2) {
                        LOG.error("Map at {} has {} type arguments (expected 2)", fullLocation, typeArgs.length);
                  } else {
                        Type valueType = typeArgs[1];
                        Class<?> valueClass = extractClass(valueType);

                        if (valueClass == null) {
                              LOG.error("Map at {} has generic value type {} that could not be resolved to a class",
                                    fullLocation, valueType);
                        } else {
                              // V is Object → type-erased, reflection cannot determine actual content.
                              // Default to string; enrichment extractors (XSD, Constants, factory params)
                              // refine individual properties to their real types in later phases.
                              if (valueClass == Object.class) {
                                    propNode.setSchemaField("additionalProperties", false);

                                    // Check @MapKeys on getter for typed map key injection
                                    MapKeys mapKeysAnnotation = getter != null ? getter.getAnnotation(MapKeys.class) : null;
                                    if (mapKeysAnnotation != null) {
                                          try {
                                                Class<?> constantsClass = Class.forName(mapKeysAnnotation.constantsClass());
                                                Map<String, Object> knownProperties = scanConstantsForMapKeys(constantsClass);
                                                if (!knownProperties.isEmpty()) {
                                                      String simpleName = constantsClass.getSimpleName().replace("Constants", "Properties");
                                                      Map<String, Object> enrichment = new LinkedHashMap<>();
                                                      enrichment.put("properties", knownProperties);
                                                      enrichment.put("x-java-class", mapKeysAnnotation.constantsClass() + "." + simpleName);
                                                      ir.enrich(fullLocation, enrichment);
                                                }
                                          } catch (ClassNotFoundException e) {
                                                LOG.warn("@MapKeys constants class not found: {}", mapKeysAnnotation.constantsClass());
                                          }
                                    }

                              // V is a primitive/wrapper/String → Simple scalars.
                              } else if (valueClass.isPrimitive() || isWrapperType(valueClass) || isStringLikeType(valueClass)) {
                                    SchemaIR.PropertyNode tempNode = new SchemaIR.PropertyNode("temp");
                                    handlePrimitiveIR(valueClass, null, tempNode);
                                    propNode.setSchemaField("additionalProperties", tempNode.getSchema());

                              // V is an enum → Same as primitive but with an enum constraint.
                              } else if (valueClass.isEnum()) {
                                    SchemaIR.PropertyNode tempNode = new SchemaIR.PropertyNode("temp");
                                    handleEnumIR(valueClass, null, tempNode);
                                    propNode.setSchemaField("additionalProperties", tempNode.getSchema());

                              // V is a nested container (Collection or Map) → unwrap recursively.
                              // Each layer adds one additionalProperties level in the emitted schema,
                              // matching broker.properties: prop.<k1>.<k2>...<kN>.<field>=value
                              } else if ((Collection.class.isAssignableFrom(valueClass)
                                          || Map.class.isAssignableFrom(valueClass))
                                    && valueType instanceof ParameterizedType) {

                                    int nestingDepth = 0;
                                    Type currentType = valueType;
                                    Class<?> currentClass = valueClass;

                                    while (currentClass != null && currentType instanceof ParameterizedType) {
                                          ParameterizedType pt = (ParameterizedType) currentType;
                                          if (Collection.class.isAssignableFrom(currentClass)) {
                                                currentType = pt.getActualTypeArguments()[0];
                                                currentClass = extractClass(currentType);
                                                nestingDepth++;
                                          } else if (Map.class.isAssignableFrom(currentClass)) {
                                                currentType = pt.getActualTypeArguments()[1];
                                                currentClass = extractClass(currentType);
                                                nestingDepth++;
                                          } else {
                                                break;
                                          }
                                    }

                                    if (currentClass != null && !currentClass.isPrimitive()
                                          && !isWrapperType(currentClass) && !isStringLikeType(currentClass)
                                          && !currentClass.isEnum() && currentClass != Object.class) {
                                          propNode.setTargetClassName(currentClass.getName());
                                          propNode.setPropertyType(SchemaIR.PropertyType.MAP_COLLECTION_VALUE);
                                          propNode.setCollectionNestingDepth(nestingDepth);
                                          propNode.setLocation(fullLocation);
                                          Location wildcardLocation = fullLocation.wildcard();
                                          for (int i = 0; i < nestingDepth; i++) {
                                                wildcardLocation = wildcardLocation.wildcard();
                                          }
                                          buildClassIR(currentClass, wildcardLocation, null);
                                    } else {
                                          LOG.warn("Map with nested containers at {} — leaf type {} is scalar, "
                                                + "defaulting to string additionalProperties",
                                                fullLocation, currentClass);
                                          Map<String, Object> valueSchema = new LinkedHashMap<>();
                                          valueSchema.put("type", new SchemaType(SchemaType.Kind.STRING).toSchemaValue());
                                          propNode.setSchemaField("additionalProperties", valueSchema);
                                    }

                              // V is a complex class → The map values have their own properties
                              // (like TransportConfiguration has factoryClassName, params, name).
                              // We recurse into that class and build its IR. If it's TransportConfiguration
                              // specifically, we also detect factory polymorphism (Netty vs InVM → oneOf).
                              } else {
                                    propNode.setTargetClassName(valueClass.getName());
                                    propNode.setPropertyType(SchemaIR.PropertyType.MAP_VALUE);
                                    propNode.setLocation(fullLocation);
                                    Location wildcardLocation = fullLocation.wildcard();
                                    buildClassIR(valueClass, wildcardLocation, null);

                                    List<Class<?>> subclasses = discoverSubclasses(valueClass);
                                    if (!subclasses.isEmpty()) {
                                          SchemaIR.ClassNode baseNode = ir.getOrCreateNode(valueClass.getName());
                                          for (Class<?> subclass : subclasses) {
                                                buildClassIR(subclass, wildcardLocation, null);
                                                baseNode.addSubclass(subclass.getName());
                                                SchemaIR.ClassNode subNode = ir.getOrCreateNode(subclass.getName());
                                                subNode.setSuperclass(valueClass.getName());
                                          }
                                    }
                              }
                        }
                  }
            } else {
                  // Raw Map without generic type parameters — cannot determine value type.
                  LOG.warn("Map property at {} has no generic type info, schema will be untyped", fullLocation);
                  propNode.setPropertyType(SchemaIR.PropertyType.MAP_VALUE);
            }
      }

      /**
       * Build IR for a {@code Collection}-typed property. The element type drives
       * the schema shape:
       * <ul>
       *   <li>Primitive/enum/Object elements &rarr; {@code array} with {@code items}</li>
       *   <li>Complex object elements &rarr; {@code object} with
       *       {@code additionalProperties} (broker.properties flat-key convention),
       *       plus subclass discovery for polymorphic hierarchies</li>
       * </ul>
       *
       * @param getter   read method for the property, used to resolve generic type args;
       *                 may be null if the property is write-only
       * @param setter   write method, used as fallback for generic type resolution;
       *                 may be null if the property is read-only
       * @param fullLocation typed path of this property from the config root
       * @param propNode target property node whose schema fields will be populated
       * @throws Exception if recursive introspection of a complex element type fails
       */
      private void handleCollectionIR(Method getter, Method setter, Location fullLocation, SchemaIR.PropertyNode propNode)
                  throws Exception {
            Type genericType = getter != null ? getter.getGenericReturnType() :
                                       (setter != null ? setter.getGenericParameterTypes()[0] : null);

            if (genericType instanceof ParameterizedType) {
                  ParameterizedType paramType = (ParameterizedType) genericType;
                  Type[] typeArgs = paramType.getActualTypeArguments();

                  if (typeArgs.length < 1) {
                        LOG.error("Collection at {} has no type arguments", fullLocation);
                        return;
                  }

                  Type elementType = typeArgs[0];
                  Class<?> elementClass = extractClass(elementType);

                  if (elementClass == null) {
                        LOG.error("Collection at {} has element type {} that could not be resolved",
                              fullLocation, elementType);
                        return;
                  }

                  boolean isComplexObject = !elementClass.isPrimitive() &&
                                                       !isWrapperType(elementClass) &&
                                                       !isStringLikeType(elementClass) &&
                                                       !elementClass.isEnum() &&
                                                       elementClass != Object.class;

                  // Collections of complex objects use object/additionalProperties in the schema.
                  // In broker.properties: propName.0.nestedField=value (indexed access).
                  if (isComplexObject) {
                        propNode.setSchemaField("type", new SchemaType(SchemaType.Kind.OBJECT).toSchemaValue());
                        propNode.setTargetClassName(elementClass.getName());
                        propNode.setPropertyType(SchemaIR.PropertyType.COLLECTION_ELEMENT);
                        propNode.setLocation(fullLocation);
                        Location wildcardLocation = fullLocation.wildcard();
                        buildClassIR(elementClass, wildcardLocation, null);

                        // Polymorphism: check @Discriminator annotation first, fall back to classpath scan
                        List<Class<?>> subclasses = discoverSubclasses(elementClass);
                        if (!subclasses.isEmpty()) {
                              SchemaIR.ClassNode baseNode = ir.getOrCreateNode(elementClass.getName());
                              for (Class<?> subclass : subclasses) {
                                    buildClassIR(subclass, wildcardLocation, null);
                                    baseNode.addSubclass(subclass.getName());
                                    SchemaIR.ClassNode subNode = ir.getOrCreateNode(subclass.getName());
                                    subNode.setSuperclass(elementClass.getName());
                              }
                        }


                  // Collections of scalars/enums use the standard JSON Schema array/items format.
                  // In broker.properties: propName[0]=value (simple list).
                  } else {
                        propNode.setSchemaField("type", new SchemaType(SchemaType.Kind.ARRAY).toSchemaValue());
                        SchemaIR.PropertyNode tempNode = new SchemaIR.PropertyNode("temp");

                        if (elementClass == Object.class) {
                              tempNode.setSchemaField("type", new SchemaType(SchemaType.Kind.STRING).toSchemaValue());
                        } else if (elementClass.isPrimitive() || isWrapperType(elementClass) || isStringLikeType(elementClass)) {
                              handlePrimitiveIR(elementClass, null, tempNode);
                        } else if (elementClass.isEnum()) {
                              handleEnumIR(elementClass, null, tempNode);
                        } else {
                              LOG.error("Collection element at {} has unexpected scalar type: {}",
                                    fullLocation, elementClass.getName());
                        }

                        propNode.setSchemaField("items", tempNode.getSchema());
                  }
            }
      }


      /**
       * Extract metadata from a {@link ConfigProperty} annotation on the getter or
       * setter and apply it to the property node. Setter annotations take precedence.
       * If neither method carries the annotation, this is a no-op.
       *
       * @param getter   read method, or null if the property is write-only
       * @param setter   write method, or null if the property is read-only
       * @param propNode target property node to enrich with annotation-derived fields
       *                 (description, deprecated, hot-reloadable, min/max)
       */
      private void applyConfigPropertyAnnotation(Method getter, Method setter, SchemaIR.PropertyNode propNode) {
            // Look for @ConfigProperty on setter first (more specific), then getter.
            // Most properties won't have this annotation — it's opt-in for explicit metadata.
            ConfigProperty annotation = null;
            if (setter != null) {
                  annotation = setter.getAnnotation(ConfigProperty.class);
            }
            if (annotation == null && getter != null) {
                  annotation = getter.getAnnotation(ConfigProperty.class);
            }
            if (annotation == null) {
                  return;
            }

            // Each annotation field is applied only if it differs from its "unset" default.
            // This allows partial annotation: you can set just description without touching min/max.
            if (!annotation.description().isEmpty()) {
                  propNode.setSchemaField("description", annotation.description());
            }
            if (annotation.deprecated()) {
                  propNode.setSchemaField("x-deprecated", true);
            }
            if (annotation.hotReloadable()) {
                  propNode.setSchemaField("x-hot-reloadable", true);
            }
            if (annotation.min() != Long.MIN_VALUE) {
                  propNode.setSchemaField("minimum", annotation.min());
            }
            if (annotation.max() != Long.MAX_VALUE) {
                  propNode.setSchemaField("maximum", annotation.max());
            }
      }

      /**
       * Lazily instantiate and cache a {@link ConfigurationImpl} used to read
       * default property values via getter invocation. If instantiation fails,
       * returns null and the generator proceeds without defaults.
       *
       * <p>Runtime dependencies for successful instantiation:
       * <ul>
       *   <li>artemis-server (ConfigurationImpl itself)</li>
       *   <li>artemis-core-client (TransportConfiguration, QueueConfiguration, etc.)</li>
       *   <li>commons-beanutils (used by ConfigurationImpl.populateWithProperties)</li>
       *   <li>SLF4J (logging inside ConfigurationImpl constructor)</li>
       * </ul>
       *
       * <p>If any of these are missing from the classpath, instantiation will throw
       * and the schema will be generated without default values (types and structure
       * are still correct, only "default" fields will be absent).
       *
       * @return a live {@link Configuration} instance, or null if construction failed
       */
      private Configuration getConfigInstance() {
            if (configInstance == null) {
                  try {
                        configInstance = new ConfigurationImpl();
                        LOG.debug("ConfigurationImpl instantiated for default value extraction");
                  } catch (Throwable e) {
                        throw new IllegalStateException(
                              "Cannot instantiate ConfigurationImpl. Ensure artemis-server, " +
                              "artemis-core-client, and commons-beanutils are on the classpath.", e);
                  }
            }
            return configInstance;
      }

      /**
       * True if the type is a boxed primitive (Boolean, Integer, Long, etc.).
       * These map to JSON Schema primitive types the same way their unboxed counterparts do.
       */
      private boolean isWrapperType(Class<?> type) {
            return type == Boolean.class || type == Integer.class || type == Long.class ||
                      type == Double.class || type == Float.class || type == Short.class ||
                      type == Byte.class || type == Character.class;
      }

      /**
       * True if the type should be represented as a JSON Schema "string".
       * Includes String, CharSequence, and path/URL types that serialize to string
       * in broker.properties.
       */
      /**
       * Find a fluent setter (returns non-void) that java.beans.Introspector misses.
       * Scans the declaring class of the getter for a method named set<PropName> that
       * accepts the property type as its single parameter.
       */
      private Method findFluentSetter(String propName, Class<?> propType, Method getter) {
            if (getter == null || propType == null) {
                  return null;
            }
            String setterName = "set" + Character.toUpperCase(propName.charAt(0)) + propName.substring(1);
            Class<?> declaringClass = getter.getDeclaringClass();
            try {
                  return declaringClass.getMethod(setterName, propType);
            } catch (NoSuchMethodException e) {
                  // Also try with primitive/wrapper variants
                  Class<?> alt = propType.isPrimitive() ? toWrapper(propType) : toPrimitive(propType);
                  if (alt != null) {
                        try {
                              return declaringClass.getMethod(setterName, alt);
                        } catch (NoSuchMethodException e2) {
                              // no setter found
                        }
                  }
            }
            return null;
      }

      private static Class<?> toWrapper(Class<?> primitive) {
            if (primitive == boolean.class) return Boolean.class;
            if (primitive == int.class) return Integer.class;
            if (primitive == long.class) return Long.class;
            if (primitive == double.class) return Double.class;
            if (primitive == float.class) return Float.class;
            if (primitive == short.class) return Short.class;
            if (primitive == byte.class) return Byte.class;
            return null;
      }

      private static Class<?> toPrimitive(Class<?> wrapper) {
            if (wrapper == Boolean.class) return boolean.class;
            if (wrapper == Integer.class) return int.class;
            if (wrapper == Long.class) return long.class;
            if (wrapper == Double.class) return double.class;
            if (wrapper == Float.class) return float.class;
            if (wrapper == Short.class) return short.class;
            if (wrapper == Byte.class) return byte.class;
            return null;
      }

      private boolean isStringLikeType(Class<?> type) {
            return type == String.class || type == CharSequence.class ||
                      type.getName().equals("java.io.File") ||
                      type.getName().equals("java.net.URL") ||
                      type.getName().equals("java.net.URI") ||
                      type.getName().equals("org.apache.activemq.artemis.api.core.SimpleString");
      }

      /**
       * Discover subclasses for a type: @Discriminator annotation first, then fallback to classpath scan.
       */
      private List<Class<?>> discoverSubclasses(Class<?> baseClass) {
            Discriminator discriminator = baseClass.getAnnotation(Discriminator.class);
            if (discriminator != null) {
                  List<Class<?>> subclasses = new ArrayList<>();
                  for (Discriminator.Mapping mapping : discriminator.value()) {
                        subclasses.add(mapping.subtype());
                  }
                  return subclasses;
            }
            return polymorphismResolver.findSubclasses(baseClass);
      }

      /**
       * Resolve a {@link Type} to a raw {@link Class}, unwrapping parameterised types.
       *
       * @param type the type to resolve (may be a Class, ParameterizedType, or other)
       * @return the raw class, or {@code null} if resolution fails (e.g. TypeVariable, WildcardType)
       */
      private Class<?> extractClass(Type type) {
            if (type instanceof Class) {
                  return (Class<?>) type;
            } else if (type instanceof ParameterizedType) {
                  return extractClass(((ParameterizedType) type).getRawType());
            }
            return null;
      }

}
