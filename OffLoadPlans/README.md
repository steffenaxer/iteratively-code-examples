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
- **Current iteration tracking**: Each proxy tracks the current iteration to ensure score updates are persisted with the correct `lastUsedIter` value

### Score Persistence and Iteration Tracking

When MATSim's scoring phase updates plan scores by calling `plan.setScore()`, the `PlanProxy` ensures that:

1. The score is immediately persisted to the plan store via `store.updateScore()`
2. The `lastUsedIter` field in the store is updated with the **current iteration** number (not the creation iteration)
3. This allows the offload system to track which iteration a plan was last used/scored

The current iteration is set on each proxy at the start of every iteration via `proxy.setCurrentIteration(currentIter)`, ensuring accurate tracking throughout the simulation lifecycle.

## Workflow

### Iteration Start

```java
// 1. Load all plans as proxies (scores in memory) and set current iteration
OffloadSupport.loadAllPlansAsProxies(person, store, currentIteration);

// 2. Materialize selected plan for simulation
OffloadSupport.ensureSelectedMaterialized(person, store, cache);
```

### During Scoring

When MATSim's scoring phase runs:
- `plan.setScore(newScore)` is called on the selected (materialized) plan
- The proxy intercepts this and updates the store with the current iteration number
- This ensures `lastUsedIter` reflects when the plan was actually scored

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

- **`loadAllPlansAsProxies(Person, PlanStore, int currentIteration)`**
  - Loads all stored plans as lightweight proxies
  - Sets the current iteration on each proxy for accurate score tracking
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

- **`setCurrentIteration(int iteration)`**
  - Sets the current iteration for this proxy
  - Called at iteration start to ensure score updates track the correct iteration
  
- **`getScore()` / `setScore(Double)`**
  - Score access without materialization
  - Score updates are persisted to store with current iteration number
  
- **`getPlanElements()`**
  - Triggers lazy materialization
  - Returns full plan data
  
- **`dematerialize()`**
  - Drops materialized plan to save memory
  - Keeps proxy with score intact

## Performance Optimizations

### Write Performance Optimizations (Latest)

The latest version includes several critical optimizations focused on **write performance**:

1. **Elimination of DB Lookups During Flush (BIGGEST IMPROVEMENT)**
   - Added `creationIterCache` to track creation iterations in memory
   - **Before**: `planDataMap.get()` called for every plan during flush → thousands of slow DB accesses
   - **After**: Direct cache lookup → zero DB accesses during flush
   - **Impact**: Drastically reduces flush time, especially for large batches

2. **Increased Write Buffer**
   - Buffer size: 50,000 → 100,000 entries
   - Fewer flush operations = better amortization of flush overhead
   - Larger batches for MapDB's bulk write operations

3. **Optimized Data Serialization**
   - New `serializeDirect()` method with pre-allocated buffers
   - Exact size calculation eliminates buffer resizing
   - Avoids creating temporary `PlanData` objects during flush
   - Reduced memory allocations and GC pressure

4. **Pre-Computed Keys**
   - Keys computed once in `PendingWrite` record
   - Eliminates repeated string concatenation during flush
   - Reduces CPU overhead and string allocations

5. **Reduced Lock Contention**
   - Flush operation moved outside synchronized block
   - Cache updates use `putIfAbsent` instead of `containsKey + put`
   - Minimized time in critical sections

6. **Enhanced MapDB Configuration**
   - Transaction support for consistent batch commits
   - Moderate initial allocation: 256 MB (reasonable for most use cases)
   - Moderate increment: 128 MB
   - Reduces file fragmentation while avoiding excessive memory usage

### MapDB Layer

- **Consolidated PlanData**: Single map instead of 7 separate maps
- **Bulk writes**: Uses `putAll()` for batch operations
- **Write buffering**: 100,000 entry buffer before flush (increased from 50,000)
- **Compression**: `SerializerCompressionWrapper` for plan data
- **Async writes**: `executorEnable()` for non-blocking persistence
- **Memory-mapped files**: Fast file I/O
- **Transaction commits**: Batch commits for consistency

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
    <param name="storageBackend" value="MAPDB" />  <!-- or ROCKSDB -->
</module>
```

### Storage Backend Options

**MapDB** (default):
- Java-based embedded database
- Good for moderate-sized simulations
- Proven stability
- Optimized with transaction batching

**RocksDB**:
- High-performance key-value store from Facebook
- Better for large-scale simulations
- Native C++ implementation with JNI bindings
- LZ4 compression enabled
- Optimized for write-heavy workloads
- May offer better performance for very large datasets

Choose RocksDB if:
- You have millions of plans to store
- Write performance is critical
- You need the absolute best throughput

Choose MapDB if:
- You prefer pure Java solution
- Your simulation is moderate-sized
- You want simpler deployment (no native libraries)

## Usage Example

See `OffloadModuleExample.java` in the test sources for a complete working example.

```java
Config config = ConfigUtils.loadConfig("config.xml");
OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
offloadConfig.setStoreDirectory("planstore");
offloadConfig.setCacheEntries(2000);
offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);  // or MAPDB

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

Or run a specific test:
```bash
# Run the materialization constraint validation test
mvn test -Dtest=OffloadModuleExampleTest

# Or use the convenience script
./run-test.sh
```

Key test classes:
- `PlanProxyTest` - Tests proxy lifecycle and lazy loading
- `OffloadModuleIT` - Integration test with full MATSim simulation
- `OffloadModuleExampleTest` - Validates that at most 1 plan is materialized per person at runtime
- `FuryRoundtripTest` - Serialization round-trip tests

### Materialization Constraint Validation

The `OffloadModuleExampleTest` includes comprehensive validation that ensures:
- **At iteration start**: At most 1 plan is materialized per person (the selected plan)
- **At iteration end**: 0 plans are materialized (all are dematerialized)
- **During simulation**: Memory footprint is minimal while maintaining full selector functionality

This validates the core promise of the PlanProxy architecture: keeping all plan scores in memory for proper selection while materializing at most 1 plan per person at any given time.

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
