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
 * Marks a Collection or Set getter as a named-map in the broker.properties namespace.
 *
 * <p>Broker properties address list/set elements by name (e.g.,
 * {@code bridgeConfigurations.myBridge.queueName}), not by index. This annotation
 * tells the schema generator to emit the collection as {@code type: object} with
 * {@code additionalProperties} keyed by the element's name, rather than as a JSON array.
 *
 * <p>Usage:
 * <pre>
 * &#64;ConfigMap
 * public List&lt;BridgeConfiguration&gt; getBridgeConfigurations() { ... }
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConfigMap {

   /**
    * Method name on the element type that returns the map key.
    * Defaults to {@code "getName"} which covers most Artemis config types.
    */
   String keyMethod() default "getName";
}
