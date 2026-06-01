# Artemis JSON Schema Generator

Generates JSON Schema (Draft 7) for Apache Artemis broker configuration validation.

## Quick Start

```bash
# Generate schema (requires artemis-server to be built first)
cd artemis-jsonschema
mvn process-classes -Pgenerate-schema -Dgenerate-schema -DskipTests

# Output at:
# target/schema/org.apache.artemis/jsonschema/broker-config-schema.json
```

## Architecture

The schema generator uses a two-layer architecture:

1. **Annotations on config source classes** — domain knowledge about types, polymorphism,
   factory params, and map keys lives as annotations in `artemis-config-api`, applied on
   `ConfigurationImpl` and all nested config types.
2. **Generic IRBuilder + emitters** — reads annotations via reflection, builds an intermediate
   representation, enriches with Javadoc/XSD descriptions, and emits JSON Schema.

This design means adding a new config property requires only annotating the Java source —
no generator code changes needed.

### Pipeline Phases

| Phase | Component | Input | Output |
|-------|-----------|-------|--------|
| 1 | `IRBuilder` | `ConfigurationImpl.class` + annotations | `SchemaIR` graph |
| 2 | `Enricher` with 2 `Extractor` implementations | Source files, XSD | Enriched IR |
| 3 | `SchemaEmitter` + 4 `PropertyEmitter` strategies | Enriched IR | JSON Schema |

### Annotations (in `artemis-config-api`)

| Annotation | Target | Purpose |
|-----------|--------|---------|
| `@ConfigProperty` | getter/setter | Marks a schema-visible property. Gates Javadoc extraction. Optional `description` override, `hotReloadable`, `deprecated`, `min`/`max`, `enumValues`. |
| `@ConfigMap` | collection/set getter | Declares a `List`/`Set` as a named-map in the properties namespace (keyed by `getName()`). |
| `@ByteNotation` | long getter | Emits `[integer, string]` union with byte-notation pattern (`25K`, `10M`). |
| `@Discriminator` | class/interface | Declares polymorphic subtypes with field-based discriminator (`type`, `storeType`). |
| `@FactoryParams` | factory class | Points to the constants class defining valid parameter keys for this factory. |
| `@FactoryType` | factory class | Declares whether a transport factory is `ACCEPTOR`, `CONNECTOR`, or `BOTH`. |
| `@MapKeys` | map getter | Points to a constants class defining known keys for an opaque `Map<String, Object>`. |
| `@InitKeys` | plugin/factory class | Declares known init map keys with their JSON Schema types. |
| `@InternalOpaqueProperty` | getter | Escape hatch: explicitly excludes a property from the schema with a documented reason. |

### Enrichment Extractors

| Extractor | Parses | Contributes |
|-----------|--------|-------------|
| `SetterGetterJavadocExtractor` | Configuration interface JavaDoc | Descriptions for `@ConfigProperty`-annotated methods |
| `XsdExtractor` | `artemis-configuration.xsd` | Path-dependent descriptions, enums, constraints |

### Package Structure

```
jsonschema/
  Pipeline.java            -- orchestrator
  config/                  -- SchemaGeneratorConfig (enrichment paths, XSD location)
  enrichment/              -- Enricher, Extractor interface, 2 extractors
  ir/                      -- SchemaIR, IRBuilder, SchemaEmitter, SchemaType, ...
  emitters/                -- PropertyEmitter + 4 strategies (Primitive, NestedObject, Map, Collection)
  validation/              -- SchemaValidator

artemis-config-api/        -- annotation definitions (zero dependencies)
```

## Design Decisions

See [docs/architecture/ADR-001-broker-properties-schema.md](../docs/architecture/ADR-001-broker-properties-schema.md)
for the full rationale: why JSON Schema, why custom annotations instead of Jackson, why
a dedicated `artemis-config-api` module, and what the schema does NOT solve.

### Annotations over heuristics

The generator previously used ~2,200 lines of heuristic code: classpath scanning for factory
implementations, source code regex for plugin init keys, constant-name pattern matching for
factory parameters, and `populateWithProperties()` source parsing for byte-notation converters.

These heuristics were fragile — every new config field required understanding the generator's
internals. They have been replaced by annotations on the config source classes. The cost of
a new config field is now O(1): add the annotation alongside the getter.

### Strict mode

If the IRBuilder encounters an opaque type (`Map<String,Object>`, `List<ComplexType>`) without
a guiding annotation (`@ConfigMap`, `@MapKeys`, `@FactoryParams`, `@InitKeys`), it fails with
a self-documenting error explaining which annotation to add. `@InternalOpaqueProperty(reason="...")`
is the escape hatch — it skips the field with a build warning.

### No default values in the schema

Default values are NOT extracted from code. There are three layers of defaults in Artemis
(Java field initializers, XML parser overrides, `artemis create` template values) and no
single source of truth that code inspection can capture.

### XSD does not contribute types

The XSD declares types for XML configuration. The schema targets broker.properties, where
Java types (via reflection) are the truth. XSD contributes descriptions only.

## Configuration

`src/main/resources/META-INF/schema-generator-config.json`:

- `ignoredProperties`: property names excluded from IR traversal
- `javadocSourceDirs`: source directories for Javadoc extraction
- `xsdPath`: path to `artemis-configuration.xsd`
- `xsdComplexTypeToPathPattern`: maps XSD complexType names to property path prefixes
- `enrichmentPathAliases`: path rewrites where XSD structure doesn't match Java nesting
- `prettyPrint`: JSON output formatting

## Extension Guide

### Adding a new broker configuration property

1. Add getter/setter to `ConfigurationImpl` (or nested config type)
2. Add `@ConfigProperty` on the getter
3. If the property is a `long` accepting byte notation: add `@ByteNotation`
4. If hot-reloadable: use `@ConfigProperty(hotReloadable = true)`
5. The schema updates automatically on next build

### Adding a new named collection

1. Add `@ConfigMap` on the collection getter
2. If the element type is polymorphic: add `@Discriminator` on the element class

### Adding a new factory-polymorphic type

1. Add `@FactoryParams(constantsClass = MyConstants.class)` on the factory implementation
2. Add `@FactoryType(Kind.ACCEPTOR)` (or `CONNECTOR`, `BOTH`) if it's a transport factory
3. The IRBuilder discovers it via classpath scanning of `@FactoryParams` annotations

### Adding a new plugin with typed init keys

1. Add `@InitKeys({@Key(name = "myKey", type = "boolean"), ...})` on the plugin class

### Adding known keys to an opaque map

1. Add `@MapKeys(constantsClass = "com.example.MyConstants")` on the map getter

### Excluding an internal property from the schema

1. Add `@InternalOpaqueProperty(reason = "Runtime-only, not user-configurable")` on the getter

## Testing

```bash
# All tests (unit + integration)
mvn test -pl artemis-jsonschema

# Generate schema and verify
mvn process-classes -pl artemis-jsonschema -Pgenerate-schema -Dgenerate-schema -DskipTests
```

## License

Apache License 2.0 -- See LICENSE file in repository root.
