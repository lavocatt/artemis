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
 * Marks a configuration property for JSON Schema extraction.
 *
 * <p>When present on a getter/setter or field, the schema generator will:
 * <ul>
 *   <li>Include this property in the generated schema
 *   <li>Use the annotation's description as override (if set), or extract from Javadoc
 *   <li>Apply explicit constraints and deprecation metadata
 * </ul>
 *
 * <p>This annotation gates the Javadoc extractor: only annotated methods get their Javadoc
 * extracted for schema descriptions. The {@code description} field is an override for when
 * Javadoc is absent or wrong — Javadoc remains the primary description source.
 *
 * <p>Usage:
 * <pre>
 * &#64;ConfigProperty
 * public int getMaxDiskUsage() { ... }
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConfigProperty {

   /**
    * Human-readable description override. If empty, the generator falls back to Javadoc
    * extraction from the annotated method.
    */
   String description() default "";

   /** Whether this property can be changed without broker restart. */
   boolean hotReloadable() default false;

   /** Whether this property is deprecated. */
   boolean deprecated() default false;

   /** Replacement property path if deprecated (e.g., "addressSettings.*.maxSizeBytes"). */
   String replacedBy() default "";

   /** Minimum value constraint (for numeric types). Use Long.MIN_VALUE for "no minimum". */
   long min() default Long.MIN_VALUE;

   /** Maximum value constraint (for numeric types). Use Long.MAX_VALUE for "no maximum". */
   long max() default Long.MAX_VALUE;

   /** Allowed values for array items (enum constraint on items). Empty means no constraint. */
   String[] enumValues() default {};
}
