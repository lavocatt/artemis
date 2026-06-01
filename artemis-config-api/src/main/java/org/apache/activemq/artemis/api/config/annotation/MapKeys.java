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
package org.apache.activemq.artemis.api.config.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a constants class whose {@code public static final String} fields define
 * the known keys for an opaque {@code Map<String, Object>} property.
 *
 * <p>The schema generator scans the constants class to materialize typed property definitions
 * inside the map, replacing heuristic constant scanning from build-time configuration.
 *
 * <p>Usage on a map getter:
 * <pre>
 * &#64;MapKeys(constantsClass = AMQPBridgeConstants.class)
 * public Map&lt;String, Object&gt; getProperties() { ... }
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MapKeys {

   /** Fully qualified name of the constants class whose {@code public static final String} fields define valid map keys. */
   String constantsClass();
}
