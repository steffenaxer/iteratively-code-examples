# MATSim Plan Offloading Module

A performance optimization module for MATSim that offloads agent plans to MapDB storage, reducing memory consumption while maintaining full compatibility with MATSim's replanning system.

## Overview

This module implements a multi-plan proxy architecture that allows MATSim to work with large populations without loading all plan details into memory. Only plan metadata (scores, types, iteration info) is kept in memory, while full plan content is stored in MapDB and materialized on demand.

## Key Features

- **Memory Efficient**: Only loads plan headers (score, type, metadata) into memory
- **Lazy Materialization**: Full plan content is loaded only when accessed
- **Full MATSim Compatibility**: Works seamlessly with ChangeExpBeta and other plan selectors
- **Performance Optimized**: MapDB backend with compression and async writes
- **Transparent Integration**: Drop-in replacement via Guice module

## Architecture

### Multi-Plan Proxy System

The module uses a proxy pattern to keep all plans in memory as lightweight objects:

1. **Plan Loading** (Iteration Start):
   - `OffloadSupport.loadAllPlansAsProxies()` loads all plans as `PlanProxy` objects
   - Each proxy contains only a `PlanHeader` (score, type, creation/usage metadata)
   - Plan content (activities, legs, routes) is stored in MapDB

2. **Plan Selection** (During Iteration):
   - MATSim's plan selectors (ChangeExpBeta, etc.) work with all proxy plans
   - Proxies delegate score/type access to the header (no materialization needed)
   - Only when plan content is accessed (e.g., for execution) is the full plan materialized

3. **Plan Persistence** (Iteration End):
   - `OffloadSupport.persistAllMaterialized()` writes modified plans back to MapDB
   - Scores are always updated for all plans (even non-materialized ones)
   - Plans are cleared from memory after persistence

### Key Classes

- **`PlanProxy`**: Lightweight proxy implementing the `Plan` interface
  - Holds only `PlanHeader` in memory
  - Materializes full plan on first content access
  - Automatically updates store when score changes

- **`PlanStore`** / **`MapDbPlanStore`**: Storage backend
  - Persists plans as compressed binary blobs (Fury serialization)
  - Stores metadata separately for fast header access
  - Manages plan limits per agent

- **`OffloadSupport`**: Utility class for proxy lifecycle
  - `loadAllPlansAsProxies()`: Load all plans as proxies
  - `persistAllMaterialized()`: Save modified plans
  - `addNewPlan()`: Add new plans during replanning

- **`OffloadIterationHooks`**: Lifecycle integration
  - Loads proxies at iteration start
  - Persists changes at iteration end

- **`LazyOffloadPlanSelector`**: Wrapper for plan selectors
  - Allows standard selectors to work with proxies
  - No materialization during selection

## Usage

### Add Module to MATSim Scenario

```java
Config config = ConfigUtils.loadConfig("config.xml");
Scenario scenario = ScenarioUtils.loadScenario(config);

Controler controler = new Controler(scenario);
controler.addOverridingModule(new OffloadModule());
controler.run();
```

### Configuration

```xml
<module name="offload">
    <param name="storeDirectory" value="/path/to/planstore" />
    <param name="cacheEntries" value="2000" />
</module>
```

Or programmatically:

```java
OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
offloadConfig.setStoreDirectory("/path/to/planstore");
offloadConfig.setCacheEntries(2000);
```

### Plan Limit Configuration

Set max plans per agent in standard MATSim config:

```xml
<module name="replanning">
    <param name="maxAgentPlanMemorySize" value="3" />
</module>
```

## How It Works

### Iteration Flow

1. **Iteration Start**:
   ```
   Population → Load Headers from MapDB → Create PlanProxy objects → Person.plans
   ```

2. **During Iteration**:
   ```
   Plan Selection → Works on proxy.score (no materialization)
   Plan Execution → proxy.getPlanElements() → Materializes from MapDB
   ```

3. **Iteration End**:
   ```
   Person.plans → Check materialized → Write to MapDB → Clear plans
   ```

### Memory Savings

For a typical agent with 3 plans:
- **Without offloading**: ~10 KB per agent (full plan objects)
- **With offloading**: ~200 bytes per agent (proxies with headers)
- **Savings**: ~98% memory reduction

## Performance Considerations

### MapDB Optimizations

The module includes several MapDB optimizations:
- Async writes via `executorEnable()`
- Compression for plan blobs
- Memory-mapped files for fast access
- Consolidated data structures (fewer maps)

### When to Use

✅ **Good for**:
- Large populations (>100k agents)
- Long-running simulations
- Memory-constrained environments
- Multiple plan alternatives per agent

❌ **Not recommended for**:
- Small populations (<10k agents) - overhead not worth it
- Scenarios with frequent plan modifications
- Real-time simulations requiring minimal latency

## Testing

Run integration tests:

```bash
mvn test -Dtest=OffloadModuleIT
```

Tests verify:
- Plan persistence and retrieval
- Score updates
- Lazy materialization
- Plan limit enforcement
- Integration with MATSim replanning

## Dependencies

- **MATSim**: 2026.0-2025w50 or later
- **MapDB**: 3.0.9
- **Apache Fury**: 0.6.0 (for efficient serialization)

## License

Same as parent project.

## Contributing

This is part of the iteratively-code-examples repository. See parent README for contribution guidelines.
