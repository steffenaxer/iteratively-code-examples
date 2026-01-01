# MATSim Plan Offloading Module - PlanProxy Architecture

## Overview

This module implements memory-efficient plan offloading for MATSim simulations using MapDB persistence and a PlanProxy architecture. The key innovation is keeping ALL plans in memory as lightweight proxies (with scores only), enabling proper plan selection algorithms like ChangeExpBeta while minimizing memory usage.

## Architecture

### Core Components

1. **PlanProxy** - Lightweight plan wrapper that holds only metadata (score, type, creation iteration)
2. **MapDbPlanStore** - Persistent storage for plan data using MapDB
3. **OffloadSupport** - Helper methods for proxy lifecycle management
4. **OffloadIterationHooks** - Integration with MATSim iteration lifecycle

### Key Design Principles

- **All plans as proxies**: Every plan is kept in memory as a `PlanProxy` object
- **Lazy materialization**: Full plan data is loaded only when accessing plan elements
- **Automatic dematerialization**: After persistence, materialized plans are dropped to save memory
- **Score-based selection**: Plan selectors can work with all plans' scores without materialization

## Workflow

### Iteration Start

```java
// 1. Load all plans as proxies (scores in memory)
OffloadSupport.loadAllPlansAsProxies(person, store);

// 2. Materialize selected plan for simulation
OffloadSupport.ensureSelectedMaterialized(person, store, cache);
```

### During Replanning

Plan selectors (e.g., ChangeExpBeta) see ALL plan proxies with scores:
- Can compare scores across all plans
- Can select any plan without materialization
- Only selected plan needs to be materialized for modification

### Iteration End

```java
// 1. Persist materialized and modified plans
OffloadSupport.persistAllMaterialized(person, store, iteration);

// 2. Dematerialize to save memory (proxies remain)
// This happens automatically inside persistAllMaterialized
```

## Key Methods

### OffloadSupport

- **`loadAllPlansAsProxies(Person, PlanStore)`**
  - Loads all stored plans as lightweight proxies
  - Keeps scores in memory for plan selection
  
- **`persistAllMaterialized(Person, PlanStore, int)`**
  - Saves only materialized and modified plans
  - Uses hash-based dirty checking
  - Automatically dematerializes after persistence
  
- **`addNewPlan(Person, Plan, PlanStore, int)`**
  - Persists a new plan and adds it as a proxy
  
- **`swapSelectedPlanTo(Person, PlanStore, String)`**
  - Changes selected plan without clearing all plans
  - Works with proxy-based plan lists

### PlanProxy

- **`getScore()` / `setScore(Double)`**
  - Score access without materialization
  - Score updates are persisted to store
  
- **`getPlanElements()`**
  - Triggers lazy materialization
  - Returns full plan data
  
- **`dematerialize()`**
  - Drops materialized plan to save memory
  - Keeps proxy with score intact

## Performance Optimizations

### MapDB Layer

- **Consolidated PlanData**: Single map instead of 7 separate maps
- **Bulk writes**: Uses `putAll()` for batch operations
- **Write buffering**: 50,000 entry buffer before flush
- **Compression**: `SerializerCompressionWrapper` for plan data
- **Async writes**: `executorEnable()` for non-blocking persistence
- **Memory-mapped files**: Fast file I/O

### Proxy Layer

- **Minimal memory footprint**: Only score, type, and metadata in memory
- **On-demand loading**: Plans materialized only when accessed
- **Automatic cleanup**: Dematerialization after persistence
- **Dirty checking**: Hash-based detection of modified plans

## Configuration

```xml
<module name="offload">
    <param name="cacheEntries" value="2000" />
    <param name="storeDirectory" value="/path/to/store" />
</module>
```

## Usage Example

```java
Config config = ConfigUtils.loadConfig("config.xml");
OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
offloadConfig.setStoreDirectory("planstore");
offloadConfig.setCacheEntries(2000);

Controler controler = new Controler(scenario);
controler.addOverridingModule(new OffloadModule());
controler.run();
```

## Memory Benefits

For a simulation with 10,000 agents and 5 plans each:

- **Without offloading**: ~50,000 full plans in memory (~500MB+)
- **With offloading (old approach)**: Only selected plans (~10,000 plans, ~100MB)
  - **Problem**: Plan selectors can't see alternatives
- **With PlanProxy**: All plans as proxies (~50,000 proxies, ~5MB) + selected materialized (~10,000 plans, ~100MB)
  - **Total**: ~105MB with full selector functionality

## Integration with Plan Selectors

The PlanProxy architecture is fully compatible with MATSim plan selectors:

- **ChangeExpBeta**: Can compare scores across all plans
- **BestPlanSelector**: Sees all plan scores
- **WorstPlanSelector**: Can identify worst plan for removal
- **ExpBetaPlanSelector**: Proper probability-based selection

All selectors work without materialization unless they need to access plan elements.

## Testing

Run tests with:
```bash
cd OffLoadPlans
mvn test
```

Key test classes:
- `PlanProxyTest` - Tests proxy lifecycle and lazy loading
- `OffloadModuleIT` - Integration test with full MATSim simulation
- `FuryRoundtripTest` - Serialization round-trip tests

## Technical Details

### Plan Identification

Plans are identified by a unique `planId` stored in plan attributes:
```java
plan.getAttributes().putAttribute("offloadPlanId", "p12345");
```

### Dirty Detection

Modified plans are detected using a hash of plan elements:
```java
int hash = planElements.size() + score.hashCode() + sum(element.hashCode())
```

Only plans with changed hashes are persisted.

### Thread Safety

- MapDB operations are thread-safe
- Write buffering uses synchronized blocks
- Parallel serialization but sequential DB writes

## Limitations

- Plan selection strategies that need to modify multiple plans won't benefit from lazy loading
- Very large numbers of plans per agent (>100) may still consume significant memory
- Initial iteration has higher overhead due to plan loading

## Future Enhancements

- Parallel persistence of materialized plans
- Adaptive buffer sizes based on memory pressure
- Plan compression in proxy form
- Selective dematerialization based on access patterns
