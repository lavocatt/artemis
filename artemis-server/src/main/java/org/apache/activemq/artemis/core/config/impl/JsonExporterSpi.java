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
package org.apache.activemq.artemis.core.config.impl;

import java.util.LinkedHashMap;

/**
 * SPI for schema-driven flat-properties-to-JSON transformation.
 * Implemented by artemis-jsonschema module. Discovered via ServiceLoader.
 *
 * Uses the JSON Schema to reconstruct a properly typed JSON document from
 * flat dotted-key broker properties (integers, booleans, arrays, nested objects).
 */
public interface JsonExporterSpi {

   /**
    * Transform flat dotted-key properties into a JSON string with correct types.
    *
    * @param properties ordered map of flat property key-value pairs (from exportAsProperties)
    * @return properly typed JSON string
    * @throws Exception if the schema is unavailable or transformation fails
    */
   String toJson(LinkedHashMap<String, String> properties) throws Exception;
}
