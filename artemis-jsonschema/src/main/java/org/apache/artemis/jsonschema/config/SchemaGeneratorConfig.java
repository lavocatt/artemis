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

package org.apache.artemis.jsonschema.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration loaded from META-INF/schema-generator-config.json.
 *
 * <p>After the annotation migration, most domain knowledge moved to annotations on the config
 * source classes. This config retains only enrichment source paths and output formatting.
 */
public final class SchemaGeneratorConfig {

   private static final Logger LOG = LoggerFactory.getLogger(SchemaGeneratorConfig.class);
   private static final String CONFIG_PATH = "/META-INF/schema-generator-config.json";
   private static volatile SchemaGeneratorConfig instance;

   private List<String> ignoredProperties = new ArrayList<>();

   private List<String> javadocSourceDirs = new ArrayList<>();

   private String xsdPath = "";

   private Map<String, String> xsdComplexTypeToPathPattern = new LinkedHashMap<>();

   private Map<String, String> enrichmentPathAliases = new LinkedHashMap<>();

   private boolean prettyPrint = true;

   SchemaGeneratorConfig() {}

   public List<String> getIgnoredProperties() {
      return ignoredProperties;
   }

   public void setIgnoredProperties(List<String> ignoredProperties) {
      this.ignoredProperties = ignoredProperties;
   }

   public List<String> getJavadocSourceDirs() {
      return javadocSourceDirs;
   }

   public void setJavadocSourceDirs(List<String> javadocSourceDirs) {
      this.javadocSourceDirs = javadocSourceDirs;
   }

   public String getXsdPath() {
      return xsdPath;
   }

   public void setXsdPath(String xsdPath) {
      this.xsdPath = xsdPath;
   }

   public boolean isPrettyPrint() {
      return prettyPrint;
   }

   public void setPrettyPrint(boolean prettyPrint) {
      this.prettyPrint = prettyPrint;
   }

   public Map<String, String> getXsdComplexTypeToPathPattern() {
      return xsdComplexTypeToPathPattern;
   }

   public void setXsdComplexTypeToPathPattern(Map<String, String> xsdComplexTypeToPathPattern) {
      this.xsdComplexTypeToPathPattern = xsdComplexTypeToPathPattern;
   }

   public Map<String, String> getEnrichmentPathAliases() {
      return enrichmentPathAliases;
   }

   public void setEnrichmentPathAliases(Map<String, String> enrichmentPathAliases) {
      this.enrichmentPathAliases = enrichmentPathAliases;
   }

   public static synchronized SchemaGeneratorConfig load() {
      if (instance == null) {
         try (InputStream is = SchemaGeneratorConfig.class.getResourceAsStream(CONFIG_PATH)) {
            if (is != null) {
               ObjectMapper mapper = new ObjectMapper();
               mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
               instance = mapper.readValue(is, SchemaGeneratorConfig.class);
            } else {
               LOG.warn("Config file not found: {}", CONFIG_PATH);
               instance = new SchemaGeneratorConfig();
            }
         } catch (Exception e) {
            LOG.warn("Failed to load {}: {}", CONFIG_PATH, e.getMessage());
            instance = new SchemaGeneratorConfig();
         }
      }
      return instance;
   }
}
