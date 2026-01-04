# MATSim Plan Offloading Module - PlanProxy Architecture

## Overview

This module implements memory-efficient plan offloading for MATSim simulations using MapDB/RocksDB persistence and a PlanProxy architecture. The key innovation is keeping ALL plans in memory as lightweight proxies (with scores only), enabling proper plan selection algorithms like ChangeExpBeta while minimizing memory usage.

## Architecture

### Core Components

1. **PlanProxy** - Lightweight plan wrapper that holds only metadata (score, type, creation iteration)
2. **MapDbPlanStore / RocksDbPlanStore** - Persistent storage for plan data
3. **OffloadSupport** - Helper methods for proxy lifecycle management
4. **OffloadIterationHooks** - Integration with MATSim iteration lifecycle and time-based dematerialization at iteration boundaries
5. **PlanMaterializationWatchdog** - Active background monitor that periodically checks and dematerializes old non-selected plans
6. **PlanMaterializationMonitor** - Monitoring and statistics for materialized plans
7. **MobsimPlanMaterializationMonitor** - Monitors plan materialization during the MobSim (mobility simulation) phase

### Key Design Principles

- **All plans as proxies**: Every plan is kept in memory as a `PlanProxy` object
- **Lazy materialization**: Full plan data is loaded only when accessing plan elements
- **Active watchdog monitoring**: Background thread continuously monitors and dematerializes old non-selected plans
- **Time-based dematerialization**: Non-selected plans exceeding a configurable lifetime are automatically dematerialized
- **Score-based selection**: Plan selectors can work with all plans' scores without materialization
- **Time tracking**: Track how long plans remain materialized for debugging

## Workflow

### Iteration Start

```java
// 1. Load all plans as proxies (scores in memory)
OffloadSupport.loadAllPlansAsProxies(person, store);

// 2. Materialize selected plan for simulation
OffloadSupport.ensureSelectedMaterialized(person, store, cache);

// 3. Dematerialize old non-selected plans (if auto-dematerialization enabled)
PlanMaterializationMonitor.dematerializeAllOldNonSelected(population, maxLifetimeMs);
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

## Time-Based Dematerialization Strategy

The module implements a multi-layered time-based dematerialization approach that ensures non-selected plans don't remain materialized longer than necessary:

### Dual-Layer Cleanup

Non-selected plans are automatically dematerialized when they exceed a configurable maximum lifetime (`maxNonSelectedMaterializationTimeMs`, default: 1000ms = 1 second, kept short to avoid excessive memory usage):

1. **Iteration Boundaries**: Checks and dematerializes old non-selected plans at iteration start and end
2. **Active Watchdog**: Background monitor that runs continuously from simulation startup to shutdown (configurable check interval via `watchdogCheckIntervalMs`, default: 2000ms = 2 seconds) to actively clean up old plans

### How It Works

- Each `PlanProxy` tracks its materialization timestamp
- **OffloadIterationHooks** checks at iteration start and end for old plans
- **PlanMaterializationWatchdog** runs continuously throughout the entire simulation (configurable interval) to catch plans that exceed their lifetime
- The watchdog starts when the simulation starts and stops when the simulation shuts down
- Plans materialized longer than `maxNonSelectedMaterializationTimeMs` are automatically dematerialized
- Selected plans are never subject to automatic dematerialization
- Whenever the watchdog cleans up plans, it logs statistics for monitoring

### Example Timeline

```
Simulation starts → Watchdog starts (checking every 2 seconds)
t=0ms:    Plan A (non-selected) is materialized for plan selection
t=1200ms: Watchdog check - Plan A age=1200ms > 1000ms threshold → dematerialized!
          Watchdog logs: "Dematerialized 1 non-selected plans older than 1000ms"
...
Simulation ends → Watchdog stops
```

## Key Classes and Methods

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

- **`getMaterializationTimestamp()` / `getMaterializationDurationMs()`**
  - Track when plan was materialized and for how long

### PlanMaterializationMonitor

- **`collectStats(Population)`**
  - Collects comprehensive statistics about materialized plans
  - Returns total plans, materialized plans, selected vs non-selected
  - Includes materialization duration statistics

- **`logStats(Population, String)`**
  - Logs materialization statistics for debugging
  - Useful for understanding memory usage patterns

- **`dematerializeNonSelected(Person)`**
  - Dematerializes all non-selected plans for a person
  
- **`dematerializeAllNonSelected(Population)`**
  - Dematerializes non-selected plans across entire population

- **`dematerializeOldNonSelected(Person, long)`**
  - Time-based dematerialization for plans older than threshold

### OffloadIterationHooks

Iteration lifecycle integration:
- Loads all plans as proxies at iteration start
- Ensures selected plans are materialized for simulation
- Automatically dematerializes old non-selected plans at iteration start/end
- Respects the `enableAutodematerialization` configuration flag
- Logs statistics if `logMaterializationStats` is enabled

### PlanMaterializationWatchdog

Active background monitoring:
- Runs as a daemon thread continuously throughout the entire simulation (checks every 2 seconds)
- Starts when the simulation starts (StartupEvent) and stops when simulation shuts down (ShutdownEvent)
- Continuously monitors for non-selected plans exceeding `maxNonSelectedMaterializationTimeMs`
- Automatically dematerializes old plans whenever detected
- Logs cleanup actions and statistics when dematerialization occurs
- Provides continuous protection against memory bloat from materialized plans

### MobsimPlanMaterializationMonitor

MobSim-specific monitoring during the simulation phase:
- Monitors plan materialization during the mobility simulation (MobSim)
- Important because plans can be materialized during WithinDayReplanning or other MobSim operations
- Configurable monitoring interval via `mobsimMonitoringIntervalSeconds` (default: 3600 seconds = 1 hour)
- Logs statistics as a single JSON object with essential metrics:
  - Total persons and plans
  - Materialized plans count
  - Selected vs. non-selected materialized plans
  - Materialization rate (percentage)
- Can be enabled/disabled via `enableMobsimMonitoring` configuration (default: true)
- Minimal performance overhead due to infrequent monitoring intervals
- Helps understand memory usage patterns during the most memory-intensive simulation phase

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
    <param name="storageBackend" value="ROCKSDB" />  <!-- or MAPDB -->
    <param name="enableAutodematerialization" value="true" />
    <param name="logMaterializationStats" value="true" />
    <param name="maxNonSelectedMaterializationTimeMs" value="1000" />
    <param name="watchdogCheckIntervalMs" value="2000" />
    <param name="enableMobsimMonitoring" value="true" />
    <param name="mobsimMonitoringIntervalSeconds" value="3600.0" />
</module>
```

### Configuration Parameters

**cacheEntries** (default: 1000)
- Maximum number of cached plans in memory
- Higher values improve performance but use more memory

**storeDirectory** (default: null)
- Directory for the plan store
- If null, uses `outputDirectory/planstore` or system temp directory

**storageBackend** (default: ROCKSDB)
- Storage backend: `MAPDB` or `ROCKSDB`
- See "Storage Backend Options" below

**enableAutodematerialization** (default: true)
- Automatically dematerializes non-selected plans to save memory
- When enabled, non-selected plans exceeding `maxNonSelectedMaterializationTimeMs` are dematerialized at:
  - Iteration start/end (via OffloadIterationHooks)
  - Periodically by the watchdog (every `watchdogCheckIntervalMs`)
- Ensures non-selected plans don't remain materialized longer than configured lifetime

**maxNonSelectedMaterializationTimeMs** (default: 1000)
- Maximum time in milliseconds a non-selected plan can remain materialized
- Default is 1000ms (1 second), kept short to avoid excessive memory usage
- Lower values = more aggressive cleanup, less memory but more I/O
- Higher values = less aggressive cleanup, more memory but less I/O
- Set to `0` to dematerialize immediately (most aggressive)
- Set to `Long.MAX_VALUE` to effectively disable time-based cleanup

**watchdogCheckIntervalMs** (default: 2000)
- Interval in milliseconds for the watchdog to check for old materialized plans
- Default is 2000ms (2 seconds)
- The watchdog runs continuously from simulation startup to shutdown
- Lower values = more frequent checks, faster cleanup but more CPU overhead
- Higher values = less frequent checks, less CPU but potentially longer-lived materialized plans
- Should typically be set >= `maxNonSelectedMaterializationTimeMs` for efficiency

**logMaterializationStats** (default: true)
- Logs statistics about materialized plans for debugging
- Includes:
  - Total plans vs materialized plans
  - Selected vs non-selected materialized plans
  - Materialization duration (max and average)
  - Distribution of materialized plans per person
  - Number of plans dematerialized due to age
- Useful for understanding memory usage patterns and tuning `maxNonSelectedMaterializationTimeMs` and `watchdogCheckIntervalMs`

**enableMobsimMonitoring** (default: true)
- Enables monitoring of plan materialization during the MobSim (mobility simulation) phase
- Important for understanding memory usage during the most memory-intensive phase
- Plans can be materialized during WithinDayReplanning or other MobSim operations
- When enabled, logs statistics at regular intervals during the simulation
- Can be disabled to reduce log output if only iteration-level monitoring is needed

**mobsimMonitoringIntervalSeconds** (default: 3600.0)
- Interval in simulation seconds for monitoring plan materialization during MobSim
- Default is 3600 seconds (1 hour of simulation time)
- Lower values = more frequent monitoring, more detailed insights but more log output
- Higher values = less frequent monitoring, less log output but less granular insights
- Typical values: 600-7200 seconds depending on simulation duration and detail needs
- Only applies when `enableMobsimMonitoring` is true

### Storage Backend Options

**RocksDB** (default):
- High-performance key-value store from Facebook
- Better for large-scale simulations
- Native C++ implementation with JNI bindings
- LZ4 compression enabled
- Optimized for write-heavy workloads
- May offer better performance for very large datasets

**MapDB**:
- Java-based embedded database
- Good for moderate-sized simulations
- Proven stability
- Optimized with transaction batching

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

// Enable time-based dematerialization (default: true)
offloadConfig.setEnableAutodematerialization(true);

// Set maximum lifetime for non-selected materialized plans (default: 1000ms)
offloadConfig.setMaxNonSelectedMaterializationTimeMs(1000); // 1 second - keep short to avoid excessive memory

// Set watchdog check interval (default: 2000ms)
offloadConfig.setWatchdogCheckIntervalMs(2000); // 2 seconds

// Enable materialization monitoring (default: true)
offloadConfig.setLogMaterializationStats(true);

// Enable MobSim monitoring (default: true)
offloadConfig.setEnableMobsimMonitoring(true);

// Set MobSim monitoring interval (default: 3600 seconds = 1 hour)
offloadConfig.setMobsimMonitoringIntervalSeconds(3600.0); // Monitor every hour of simulation time

Controler controler = new Controler(scenario);
controler.addOverridingModule(new OffloadModule());
controler.run();
```

### Tuning the Parameters

The `maxNonSelectedMaterializationTimeMs` and `watchdogCheckIntervalMs` parameters control the tradeoff between memory usage, I/O, and CPU overhead:

```java
// Very aggressive cleanup - minimal memory, more I/O
offloadConfig.setMaxNonSelectedMaterializationTimeMs(500);  // 0.5 seconds
offloadConfig.setWatchdogCheckIntervalMs(1000);            // check every 1 second

// Balanced (default) - good for most use cases  
offloadConfig.setMaxNonSelectedMaterializationTimeMs(1000); // 1 second
offloadConfig.setWatchdogCheckIntervalMs(2000);            // check every 2 seconds

// Relaxed - allows plans to stay materialized longer, less frequent checks
offloadConfig.setMaxNonSelectedMaterializationTimeMs(5000);  // 5 seconds
offloadConfig.setWatchdogCheckIntervalMs(5000);             // check every 5 seconds

// Immediate cleanup - dematerialize as soon as possible
offloadConfig.setMaxNonSelectedMaterializationTimeMs(0);    // immediate
offloadConfig.setWatchdogCheckIntervalMs(500);              // check very frequently
```

The `mobsimMonitoringIntervalSeconds` parameter controls monitoring frequency during MobSim:

```java
// Detailed monitoring - every 10 minutes of simulation time
offloadConfig.setEnableMobsimMonitoring(true);
offloadConfig.setMobsimMonitoringIntervalSeconds(600.0);  // every 10 minutes

// Standard monitoring (default) - good balance
offloadConfig.setEnableMobsimMonitoring(true);
offloadConfig.setMobsimMonitoringIntervalSeconds(3600.0); // every hour

// Coarse monitoring - less frequent, minimal overhead
offloadConfig.setEnableMobsimMonitoring(true);
offloadConfig.setMobsimMonitoringIntervalSeconds(7200.0); // every 2 hours

// Disable MobSim monitoring - only iteration-level stats
offloadConfig.setEnableMobsimMonitoring(false);
```

### Monitoring Output Example

When `logMaterializationStats` is enabled, you'll see output like:

```
INFO  PlanMaterializationWatchdog - Plan materialization watchdog started (check interval: 2000ms, max plan lifetime: 1000ms)

INFO  OffloadIterationHooks - Iteration 1: Dematerialized 25 non-selected plans older than 1000ms at iteration start

INFO  PlanMaterializationMonitor - Plan materialization stats at iteration 1 start: 
      MaterializationStats{totalPersons=10000, totalPlans=50000, materializedPlans=10125, 
      selectedMaterialized=10000, nonSelectedMaterialized=125, maxDuration=989ms, 
      avgDuration=512.3ms, distribution={1 materialized=10000, 2 materialized=125}}
INFO  PlanMaterializationMonitor - Materialization rate: 10125/50000 (20.25 %)

INFO  PlanMaterializationWatchdog - Watchdog: Dematerialized 15 non-selected plans older than 1000ms
INFO  PlanMaterializationMonitor - Plan materialization stats at watchdog cleanup: 
      MaterializationStats{totalPersons=10000, totalPlans=50000, materializedPlans=10000, 
      selectedMaterialized=10000, nonSelectedMaterialized=0, maxDuration=987ms, 
      avgDuration=543.7ms, distribution={1 materialized=10000}}
INFO  PlanMaterializationMonitor - Materialization rate: 10000/50000 (20.00 %)

INFO  MobsimPlanMaterializationMonitor - {"time":"01:00:00", "totalPersons":10000, "totalPlans":50000, "materializedPlans":10015, "selectedMaterialized":10000, "nonSelectedMaterialized":15, "materializationRate":20.03}

INFO  MobsimPlanMaterializationMonitor - {"time":"02:00:00", "totalPersons":10000, "totalPlans":50000, "materializedPlans":10008, "selectedMaterialized":10000, "nonSelectedMaterialized":8, "materializationRate":20.02}
```

## Memory Benefits

For a simulation with 10,000 agents and 5 plans each:

- **Without offloading**: ~50,000 full plans in memory (~500MB+)
- **With offloading (old approach)**: Only selected plans (~10,000 plans, ~100MB)
  - **Problem**: Plan selectors can't see alternatives
- **With PlanProxy + Agile Dematerialization**: All plans as proxies (~50,000 proxies, ~5MB) + selected materialized (~10,000 plans, ~100MB)
  - **Total**: ~105MB with full selector functionality
  - **Key advantage**: Non-selected plans are automatically dematerialized, ensuring minimal memory overhead

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
- `PlanMaterializationMonitorTest` - Tests monitoring, statistics, and agile dematerialization
- `FuryRoundtripTest` - Serialization round-trip tests

### Materialization Constraint Validation

The agile dematerialization approach ensures:
- **Before Mobsim**: Only selected plans are materialized
- **After Mobsim**: Non-selected plans are immediately dematerialized
- **After Replanning**: Temporary materializations during plan selection are cleaned up
- **Iteration End**: All non-selected plans are dematerialized

This validates the core promise: keeping all plan scores in memory for proper selection while ensuring non-selected plans don't remain materialized longer than necessary.

### Debugging Materialization Issues

If you see warnings about non-selected materialized plans:

1. **Check configuration**: Ensure `enableAutodematerialization=true`
2. **Review logs**: Check materialization statistics to identify patterns
3. **Inspect durations**: Look at `maxDuration` and `avgDuration` in stats
4. **Custom dematerialization**: Use `PlanMaterializationMonitor.dematerializeOldNonSelected()` with custom time thresholds

Example of programmatic monitoring:
```java
// Get current statistics
MaterializationStats stats = PlanMaterializationMonitor.collectStats(population);
System.out.println("Materialized: " + stats.materializedPlans());
System.out.println("Non-selected: " + stats.nonSelectedMaterializedPlans());

// Dematerialize plans older than 5 seconds
int dematerialized = PlanMaterializationMonitor.dematerializeAllOldNonSelected(population, 5000);
```

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
