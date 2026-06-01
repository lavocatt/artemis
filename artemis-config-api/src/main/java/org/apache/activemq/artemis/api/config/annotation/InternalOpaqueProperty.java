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
 * Explicitly marks a property as internal/opaque — not part of the public broker configuration
 * contract. The schema generator will skip this property with a build WARNING (not an error).
 *
 * <p>This is the escape hatch for edge cases that don't fit the annotation model. The developer
 * MUST provide a reason explaining why this property is excluded from the public schema. This
 * creates traced, auditable technical debt rather than silent omissions.
 *
 * <p>Usage:
 * <pre>
 * &#64;InternalOpaqueProperty(reason = "Runtime-only JMX connection pool, not user-configurable")
 * public ConnectionPool getConnectionPool() { ... }
 * </pre>
 *
 * <p>The schema generator logs a warning like:
 * <pre>
 * WARN: ConfigurationImpl.connectionPool excluded from schema (@InternalOpaqueProperty:
 *       "Runtime-only JMX connection pool, not user-configurable")
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface InternalOpaqueProperty {

   /**
    * Why this property is excluded from the public schema.
    * This string is logged at build time and serves as documentation of the decision.
    */
   String reason();
}
