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
 * Declares the known init map keys for a broker plugin, with their JSON Schema types.
 *
 * <p>Broker plugins accept configuration via an opaque {@code Map<String, String> init} parameter.
 * This annotation declares the known keys and their types, replacing heuristic source-code scanning
 * that greps for {@code properties.get("KEY")} patterns.
 *
 * <p>Usage on a plugin implementation class:
 * <pre>
 * &#64;InitKeys({
 *     &#64;InitKeys.Key(name = "LOG_ALL_EVENTS", type = "boolean"),
 *     &#64;InitKeys.Key(name = "periodSeconds", type = "integer")
 * })
 * public class LoggingActiveMQServerPlugin implements ActiveMQServerBasePlugin { ... }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface InitKeys {

   Key[] value();

   @interface Key {
      /** The init map key name. */
      String name();

      /** JSON Schema type: "string", "boolean", or "integer". Defaults to "string". */
      String type() default "string";
   }
}
