# ADR-001: Broker Configuration Schema Strategy

**Status:** Accepted  
**Date:** 2026-06-05  
**Authors:** Thomas L'Avocat  

## Context

Apache Artemis broker configuration has two ingestion paths:

1. **`broker.xml`** — XML parsed by `FileConfigurationParser`, validated against `artemis-configuration.xsd`
2. **`broker.properties` / `broker.json`** — flat key-value pairs (or JSON flattened to key-value) applied via BeanUtils introspection on `ConfigurationImpl`

The properties path uses dot-separated keys (`addressSettings.#.maxDeliveryAttempts=10`) that map to nested bean property paths. Collections are addressed by name, not index (`bridgeConfigurations.myBridge.queueName=orders`). This is not standard Java serialization — it is a domain-specific convention encoded in `ConfigurationImpl.populateWithProperties()` and `CollectionAutoFillPropertiesUtil`.

We needed a machine-readable public contract for the configuration surface. A JSON Schema was chosen over alternatives for specific reasons:

### Why a JSON Schema?

**The problem it solves:** Artemis has ~175 root configuration properties, ~75 address settings, polymorphic HA policies, transport factories with opaque parameter maps, and plugin init keys. The configuration surface is large, under-documented, and easy to misconfigure. Errors surface at runtime as cryptic BeanUtils exceptions, not at authoring time.

**Why JSON Schema specifically (and not XSD, OpenAPI, or prose docs):**

1. **IDE-native validation.** VS Code and IntelliJ natively consume JSON Schema for autocompletion, inline validation, and hover documentation — zero plugin required. The user writes `broker.json`, the IDE tells them `maxDeliveryAttempts` is an integer, `addressFullMessagePolicy` accepts `PAGE`/`DROP`/`BLOCK`/`FAIL`, and `bridgeConfigurations.*.staticConnectors` is an array. This is the highest-value deliverable for user experience.

2. **Runtime validation before apply.** `JsonSchemaValidator` validates the JSON config against the schema *before* it reaches `parsePrefixedProperties`. Schema errors name the exact path and constraint (`$.addressSettings.#.maxDeliveryAttempts: string found, integer expected`). BeanUtils errors name Java internals (`InvocationTargetException on setMaxDeliveryAttempts`). The schema catches typos, wrong types, and unknown properties at the right layer.

3. **Already the industry standard for config validation.** Kubernetes CRDs, Helm values, Terraform providers, GitHub Actions — all use JSON Schema for configuration contracts. Operators and tools in the Artemis ecosystem (OpenShift AMQ, Kubernetes operators) can consume the schema directly for CRD validation without Artemis-specific tooling.

4. **Schema describes the *document* model, not the *runtime* model.** XSD describes XML structure. The broker properties namespace is not XML — it's flat key-value pairs that happen to also be ingestible as JSON. JSON Schema describes what the JSON document should look like, which is exactly the contract users author against.

5. **Enables typed flat-to-JSON conversion.** Flat broker properties (`maxDeliveryAttempts=10`) lose all type information — everything is a string. The JSON Schema provides the type map needed to reconstruct proper JSON: integers become `10` not `"10"`, booleans become `true` not `"true"`, comma-separated strings become `["a","b"]` not `"a,b"`. Without the schema, a `properties → JSON` exporter can only guess. With it, the conversion is deterministic. This powers `ConfigurationImpl.exportAsJson()`.

### What the JSON Schema does NOT solve

The schema is **static analysis** — it validates the document structure and types at authoring time. It cannot enforce:

- **Cross-property dependencies.** `securityRoles.orders.producers.send=true` only makes sense if address `orders` is configured in `addressConfigurations`. `bridgeConfigurations.myBridge.staticConnectors=netty-connector` requires `netty-connector` to exist in `connectorConfigurations`. `clusterConfigurations.myCluster.connectorName=netty-connector` is the same pattern. The schema validates each property independently — it cannot express "this value must reference a key defined elsewhere in the same document." These are semantic constraints that only the runtime can enforce.

- **Runtime validation errors.** A config file can be schema-valid but fail at runtime: a JDBC connection URL that doesn't resolve, an LDAP server that's unreachable, a network interface name that doesn't exist on the host. The schema catches structural errors (wrong type, unknown property, missing required field); the runtime catches operational errors.

- **Ordering constraints.** Some properties must be applied before others (e.g., `HAPolicyConfiguration` type discriminator before its sub-properties, `connectorConfigurations` before `bridgeConfigurations` that reference them). The schema validates the final document shape, not the application order. `InsertionOrderedProperties` and `parsePrefixedProperties` enforce ordering at runtime.

- **Default value semantics.** When a property is absent from the config, the broker applies a default from `ActiveMQDefaultConfiguration`. The schema documents these defaults where known, but cannot enforce "if X is set, then Y must also be set" conditional logic.

The schema's value is catching the 80% of configuration errors that are structural (typos, wrong types, unknown keys) before the broker starts. The remaining 20% (semantic dependencies, operational validity) are runtime concerns.

## Decision

### Why not Jackson annotations?

Jackson annotations (`@JsonProperty`, `@JsonTypeInfo`, `@JsonSubTypes`) describe how Java objects serialize to JSON. They would enable `ObjectMapper.writeValue(config)` and `readValue(json, ConfigurationImpl.class)`.

However, the broker properties namespace is **not** a standard JSON serialization of `ConfigurationImpl`:

- **Collections are maps, not arrays.** `List<BridgeConfiguration>` is addressed as `bridgeConfigurations.<name>.<field>`, keyed by `getName()`. Jackson would serialize it as a JSON array.
- **Polymorphism uses external conventions.** `HAPolicyConfiguration` uses a scalar type discriminator (`REPLICATION_PRIMARY_QUORUM_VOTING`) set as the property value *before* sub-properties. Jackson's `@JsonTypeInfo` embeds the discriminator as a JSON field.
- **Factory parameters are opaque maps.** `TransportConfiguration.params` is `Map<String, Object>` whose valid keys are defined by `*_PROP_NAME` constants on factory-specific companion classes (`TransportConstants`). Jackson sees an opaque map.
- **Context-dependent polymorphism.** The same Java type (`TransportConfiguration`) has different allowed factory variants depending on whether it's in `acceptorConfigurations` or `connectorConfigurations`. Jackson's polymorphism is per-class, not per-usage-site.

Jackson annotations describe what the types *look like* in JSON. The schema describes how the **flat-key properties namespace** maps onto those types. These are fundamentally different contracts.

### Why custom annotations?

The schema generator (in `artemis-jsonschema`) initially used heuristics to discover the domain conventions:

- Classpath scanning for factory implementations
- `*_PROP_NAME` constant scanning for factory parameter keys
- Source code regex scanning for plugin init keys
- Property name pattern matching (e.g., "contains acceptor" → filter to AcceptorFactory)
- `populateWithProperties()` source parsing for byte-notation converter registration

These heuristics were fragile. Every new config field required understanding the generator's internals. The maintenance cost was proportional to the generator's complexity, not the change.

Custom annotations (`@ConfigMap`, `@Discriminator`, `@FactoryParams`, `@FactoryType`, `@MapKeys`, `@InitKeys`, `@ByteNotation`, `@ConfigProperty`) replace these heuristics with explicit, compiler-checked metadata on the config classes themselves. The generator becomes a generic annotation reader.

The cost of a new config field is now O(1): add the annotation alongside the getter. No generator knowledge required.

### Why a separate `artemis-config-api` module?

The annotations must be visible from both `artemis-server` (where `ConfigurationImpl` lives) and `artemis-jsonschema` (where the generator reads them). Placing them in `artemis-commons` would pollute a low-level utility module with broker-architecture concepts (`@FactoryType`, `@Discriminator`). A dedicated leaf module with zero dependencies keeps the separation clean.

### The escape hatch

`@InternalOpaqueProperty(reason = "...")` allows a developer to explicitly exclude a property from the public schema. The generator logs a warning (not an error) and skips the field. The `reason` string creates traced, auditable technical debt rather than silent omissions.

## Consequences

- The schema generator's heuristic code (~1400 lines across 7 builder/extractor classes) is replaced by annotation readers
- New config fields must be annotated; the generator fails the build with a self-documenting error if an opaque type lacks annotations
- Javadoc remains the primary description source; `@ConfigProperty` gates which methods the Javadoc extractor processes
- The XSD enricher remains for XML-path-dependent descriptions
- The generated schema must be semantically identical to the pre-annotation schema (validated by normalized diff + existing tests)

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
| Jackson annotations | Different contract (JSON ser/de vs properties namespace). See detailed analysis above. |
| Heuristic generator (status quo) | Fragile, high maintenance cost, requires generator expertise to modify config. |
| Separate DTO layer | Double the class count, mapper drift risk, no advantage over annotations on existing types. |
| `jackson-module-jsonSchema` | Cannot produce equivalent schema — collections-as-maps, factory params, context-dependent polymorphism are not expressible. |
