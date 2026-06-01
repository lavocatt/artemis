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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Properties;

import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class PropertiesToJsonExporterTest {

   private static final PropertiesToJsonExporter exporter = new PropertiesToJsonExporter();
   private static final ObjectMapper MAPPER = new ObjectMapper();

   @BeforeAll
   static void checkSchemaAvailable() {
      InputStream is = PropertiesToJsonExporterTest.class
         .getResourceAsStream("/org.apache.artemis/jsonschema/broker-config-schema.json");
      Assumptions.assumeTrue(is != null, "Schema not on classpath");
   }

   @Test
   void testIntegerTyping() throws Exception {
      LinkedHashMap<String, String> props = new LinkedHashMap<>();
      props.put("journalMinFiles", "5");
      props.put("journalFileSize", "10485760");

      String json = exporter.toJson(props);
      JsonNode root = MAPPER.readTree(json);

      assertTrue(root.get("journalMinFiles").isNumber());
      assertEquals(5, root.get("journalMinFiles").asInt());
      assertTrue(root.get("journalFileSize").isNumber());
      assertEquals(10485760, root.get("journalFileSize").asInt());
   }

   @Test
   void testBooleanTyping() throws Exception {
      LinkedHashMap<String, String> props = new LinkedHashMap<>();
      props.put("securityEnabled", "false");
      props.put("persistenceEnabled", "true");

      String json = exporter.toJson(props);
      JsonNode root = MAPPER.readTree(json);

      assertTrue(root.get("securityEnabled").isBoolean());
      assertFalse(root.get("securityEnabled").asBoolean());
      assertTrue(root.get("persistenceEnabled").isBoolean());
      assertTrue(root.get("persistenceEnabled").asBoolean());
   }

   @Test
   void testStringTyping() throws Exception {
      LinkedHashMap<String, String> props = new LinkedHashMap<>();
      props.put("name", "my-broker");
      props.put("clusterUser", "admin");

      String json = exporter.toJson(props);
      JsonNode root = MAPPER.readTree(json);

      assertTrue(root.get("name").isTextual());
      assertEquals("my-broker", root.get("name").asText());
   }

   @Test
   void testArrayTyping() throws Exception {
      LinkedHashMap<String, String> props = new LinkedHashMap<>();
      props.put("incomingInterceptorClassNames", "com.foo.A,com.foo.B");

      String json = exporter.toJson(props);
      JsonNode root = MAPPER.readTree(json);

      assertTrue(root.get("incomingInterceptorClassNames").isArray());
      assertEquals(2, root.get("incomingInterceptorClassNames").size());
      assertEquals("com.foo.A", root.get("incomingInterceptorClassNames").get(0).asText());
      assertEquals("com.foo.B", root.get("incomingInterceptorClassNames").get(1).asText());
   }

   @Test
   void testNestedObjectReconstruction() throws Exception {
      LinkedHashMap<String, String> props = new LinkedHashMap<>();
      props.put("addressSettings.#.maxDeliveryAttempts", "10");
      props.put("addressSettings.#.expiryAddress", "expiry");

      String json = exporter.toJson(props);
      JsonNode root = MAPPER.readTree(json);

      assertTrue(root.has("addressSettings"));
      assertTrue(root.get("addressSettings").has("#"));
      assertEquals(10, root.get("addressSettings").get("#").get("maxDeliveryAttempts").asInt());
      assertEquals("expiry", root.get("addressSettings").get("#").get("expiryAddress").asText());
   }

   @Test
   void testSplitKey() {
      assertEquals(java.util.List.of("a", "b", "c"), PropertiesToJsonExporter.splitKey("a.b.c"));
      assertEquals(java.util.List.of("addressSettings", "#", "maxDeliveryAttempts"),
         PropertiesToJsonExporter.splitKey("addressSettings.#.maxDeliveryAttempts"));
      assertEquals(java.util.List.of("addressConfigurations", "LB.TEST", "queueConfigs"),
         PropertiesToJsonExporter.splitKey("addressConfigurations.\"LB.TEST\".queueConfigs"));
   }

   @Test
   void testFullRoundTrip() throws Exception {
      ConfigurationImpl original = new ConfigurationImpl();
      Properties inputProps = new ConfigurationImpl.InsertionOrderedProperties();
      inputProps.put("name", "round-trip-test");
      inputProps.put("securityEnabled", "false");
      inputProps.put("journalMinFiles", "7");
      inputProps.put("globalMaxSize", "50M");
      inputProps.put("addressSettings.#.maxDeliveryAttempts", "5");
      inputProps.put("addressSettings.#.expiryAddress", "expiry");
      inputProps.put("clusterConfigurations.cc.name", "cc");
      inputProps.put("clusterConfigurations.cc.messageLoadBalancingType", "ON_DEMAND");
      original.parsePrefixedProperties(inputProps, null);
      assertTrue(original.getStatus().contains("\"errors\":[]"), original.getStatus());

      LinkedHashMap<String, String> exported = original.exportToMap();
      String json = exporter.toJson(exported);
      JsonNode root = MAPPER.readTree(json);

      assertEquals("round-trip-test", root.get("name").asText());
      assertFalse(root.get("securityEnabled").asBoolean());
      assertTrue(root.get("journalMinFiles").isNumber());
      assertEquals(7, root.get("journalMinFiles").asInt());
      assertTrue(root.has("addressSettings"));
      assertTrue(root.get("addressSettings").has("#"));
      assertEquals(5, root.get("addressSettings").get("#").get("maxDeliveryAttempts").asInt());

      // Re-import the JSON back into a fresh config
      ConfigurationImpl reloaded = new ConfigurationImpl();
      ConfigurationImpl.InsertionOrderedProperties reloadProps = new ConfigurationImpl.InsertionOrderedProperties();
      try (java.io.StringReader reader = new java.io.StringReader(json)) {
         reloadProps.loadJson(reloaded, reader);
      }
      reloaded.parsePrefixedProperties(reloadProps, null);
      assertTrue(reloaded.getStatus().contains("\"errors\":[]"), reloaded.getStatus());

      assertEquals("round-trip-test", reloaded.getName());
      assertFalse(reloaded.isSecurityEnabled());
      assertEquals(7, reloaded.getJournalMinFiles());
      assertEquals(5, reloaded.getAddressSettings().get("#").getMaxDeliveryAttempts());
   }
}
