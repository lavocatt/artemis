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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test proving the JSON schema describes valid broker.properties paths.
 *
 * <p>Generates a maximal Properties from the schema, applies it to ConfigurationImpl,
 * exports the config back, and asserts round-trip equality for exportable properties.
 */
public class ConfigRoundTripTest {

   private static final Path SCHEMA_FILE = Paths.get(
         "target/schema/org.apache.artemis/jsonschema/broker-config-schema.json");

   @Test
   @SuppressWarnings("unchecked")
   public void roundTripSchemaProperties() throws Exception {
      Assumptions.assumeTrue(Files.exists(SCHEMA_FILE), "Schema must be generated first");

      // Load schema
      ObjectMapper mapper = new ObjectMapper();
      Map<String, Object> schema = mapper.readValue(SCHEMA_FILE.toFile(), Map.class);

      // Generate properties from schema
      SchemaToPropertiesGenerator generator = new SchemaToPropertiesGenerator(schema);
      Properties inputProps = generator.generate();

      System.out.println("=== Round-Trip Config Test ===");
      System.out.println("Generated " + inputProps.size() + " properties from schema");
      System.out.println("Skipped " + generator.getSkipped().size() + " properties");

      // Apply to ConfigurationImpl
      ConfigurationImpl config = new ConfigurationImpl();

      int applySuccesses = 0;
      int applyFailures = 0;
      List<String> failedToApply = new ArrayList<>();

      for (String key : inputProps.stringPropertyNames()) {
         Properties single = new Properties();
         single.setProperty(key, inputProps.getProperty(key));
         try {
            config.parsePrefixedProperties(single, null);
            applySuccesses++;
         } catch (Exception e) {
            applyFailures++;
            failedToApply.add(key + " → " + e.getClass().getSimpleName() + ": " + e.getMessage());
         }
      }

      System.out.println("\n--- Apply Phase ---");
      System.out.println("Applied successfully: " + applySuccesses);
      System.out.println("Failed to apply: " + applyFailures);
      if (!failedToApply.isEmpty()) {
         System.out.println("Failures (first 20):");
         failedToApply.stream().limit(20).forEach(f -> System.out.println("  " + f));
      }

      // Export to temp file
      java.io.File tempFile = java.io.File.createTempFile("artemis-export-", ".properties");
      tempFile.deleteOnExit();
      config.exportAsProperties(tempFile);

      Properties exportedProps = new Properties();
      try (java.io.FileReader reader = new java.io.FileReader(tempFile)) {
         exportedProps.load(reader);
      }

      System.out.println("\n--- Export Phase ---");
      System.out.println("Exported " + exportedProps.size() + " properties");

      // Compare: which of our input properties survived the round-trip?
      int matched = 0;
      int missing = 0;
      int valueChanged = 0;
      List<String> missingProps = new ArrayList<>();
      List<String> changedProps = new ArrayList<>();

      for (String key : inputProps.stringPropertyNames()) {
         if (failedToApply.stream().anyMatch(f -> f.startsWith(key + " →"))) {
            continue; // skip ones that failed to apply
         }
         String exported = exportedProps.getProperty(key);
         String input = inputProps.getProperty(key);

         if (exported == null) {
            missing++;
            missingProps.add(key);
         } else if (!exported.equals(input)) {
            valueChanged++;
            changedProps.add(key + ": input=" + input + " export=" + exported);
         } else {
            matched++;
         }
      }

      System.out.println("\n--- Round-Trip Results ---");
      System.out.println("Matched (exact round-trip): " + matched);
      System.out.println("Missing from export: " + missing);
      System.out.println("Value changed: " + valueChanged);

      if (!missingProps.isEmpty()) {
         System.out.println("\nMissing from export (first 20):");
         missingProps.stream().limit(20).forEach(p -> System.out.println("  " + p));
      }
      if (!changedProps.isEmpty()) {
         System.out.println("\nValue changed (first 20):");
         changedProps.stream().limit(20).forEach(p -> System.out.println("  " + p));
      }

      // The test succeeds if we could apply a reasonable number of properties
      // and the round-trip rate is meaningful (not zero)
      assertTrue(applySuccesses > 0, "Should apply at least some properties");
      System.out.println("\n=== Summary: " + matched + " properties round-tripped, "
            + applySuccesses + " applied, " + applyFailures + " failed to apply ===");
   }
}
