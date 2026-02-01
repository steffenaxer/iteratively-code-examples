# MATSim Plan Offloading Module

## Project Purpose

The MATSim Plan Offloading Module solves a critical memory scaling problem in large-scale transportation simulations. In MATSim, agents (representing people) maintain multiple travel plans, and as simulations scale to millions of agents with multiple plans each, memory consumption becomes prohibitive. This module enables memory-efficient plan management while preserving full functionality of MATSim's plan selection algorithms.

## The Problem

In standard MATSim simulations:
- Each agent maintains 3-5 plans (travel alternatives)
- Each plan contains detailed route information (5-20 KB per plan)
- For 1 million agents with 5 plans each: **25-100 GB of RAM** just for plans
- Plan selection algorithms (e.g., ChangeExpBeta) need access to scores of ALL plans
- Naive offloading breaks plan selection by hiding alternatives

## The Solution: PlanProxy Architecture

This module introduces a **PlanProxy** pattern that:

1. **Keeps all plans in memory as lightweight proxies** (~100 bytes each)
   - Proxies contain: score, type, creation iteration, planId
   - Enable plan selectors to compare all alternatives
   
2. **Lazily materializes full plan data** only when needed
   - During simulation: only the selected plan is materialized
   - During replanning: plans are materialized on-demand
   
3. **Automatically persists and dematerializes** plans
   - After each iteration: modified plans saved to RocksDB
   - Non-selected plans dematerialized to save memory
   - Proxies remain in memory for next iteration

4. **Converts regular plans to proxies** after replanning
   - New plans created during replanning are automatically persisted
   - Converted to PlanProxy instances for memory efficiency
   - Ensures consistent memory footprint

## Memory Impact

For a simulation with 1 million agents and 5 plans each:

| Approach | Memory Usage | Plan Selection | Notes |
|----------|-------------|----------------|-------|
| **Standard MATSim** | 25-100 GB | ✅ Full functionality | Prohibitive for large simulations |
| **Naive Offload** | 5-20 GB | ❌ Broken (can't see alternatives) | Plan selectors don't work |
| **PlanProxy Module** | 500 MB - 2 GB | ✅ Full functionality | Best of both worlds |

**Memory Breakdown with PlanProxy:**
- 5M proxies × 100 bytes = 500 MB (all plan metadata)
- 1M materialized plans × 10 KB = 10 GB during simulation
- After dematerialization: ~500 MB + RocksDB overhead

## Key Features

### 1. Transparent Plan Selection
- ChangeExpBeta, BestPlanSelector, etc. work without modification
- All plan scores are always available in memory
- Proper probability-based selection

### 2. Automatic Lifecycle Management
- Plans automatically converted to proxies after replanning
- Materialization triggered by accessing plan elements
- Dematerialization after persistence
- No manual intervention required

### 3. High-Performance Storage
- RocksDB backend for fast read/write operations
- LZ4 compression reduces disk usage
- Optimized for write-heavy workloads
- Thread-safe operations

### 4. Memory Monitoring
- Real-time monitoring of materialized plans
- Configurable monitoring intervals
- Statistics logged during simulation

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                      MATSim Simulation                       │
├─────────────────────────────────────────────────────────────┤
│  Person                                                      │
│  ├── Plan (PlanProxy) - score: 95.2 ──┐                    │
│  ├── Plan (PlanProxy) - score: 92.1   │ All in memory      │
│  └── Plan (PlanProxy) - score: 88.5 ──┘ (~100 bytes each)  │
│                                                              │
│  Plan Selector (ChangeExpBeta)                              │
│  └── Sees all scores, selects based on exp(β × score)      │
│                                                              │
│  Selected Plan                                              │
│  └── Materialized for simulation ────────┐                 │
│                                           │                  │
│  ┌────────────────────────────────────────┼─────────────┐   │
│  │ AfterReplanningDematerializer          │             │   │
│  │  1. Convert regular plans to proxies  │             │   │
│  │  2. Persist to RocksDB ───────────────┼────────┐    │   │
│  │  3. Dematerialize non-selected ───────┼────┐   │    │   │
│  └────────────────────────────────────────┼────┼───┼────┘   │
│                                           │    │   │        │
│  ┌────────────────────────────────────────▼────▼───▼────┐   │
│  │              RocksDB Plan Store                      │   │
│  │  ┌──────────────────────────────────────────────┐   │   │
│  │  │ PersonId | PlanId → Plan Data (compressed)  │   │   │
│  │  └──────────────────────────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Package Structure

```
io.iteratively.matsim.offload
├── AfterReplanningDematerializer.java  - Converts regular plans to proxies
├── OffloadModule.java                   - Guice module for DI setup
├── OffloadIterationHooks.java          - Integration with MATSim lifecycle
├── OffloadConfigGroup.java             - Configuration options
├── OffloadSupport.java                 - Helper methods for plan management
│
├── PlanProxy.java                      - Lightweight plan wrapper
├── PlanStore.java                      - Interface for plan persistence
├── RocksDbPlanStore.java              - RocksDB implementation
├── FuryPlanCodec.java                 - Plan serialization
├── PlanStoreShutdownListener.java     - Cleanup on shutdown
│
├── PlanMaterializationMonitor.java    - Statistics collection
├── MobsimPlanMaterializationMonitor.java - Runtime monitoring
│
└── dto/
    ├── PlanDTO.java                   - Data transfer object for plans
    ├── PlanElementDTO.java            - DTO for activities/legs
    ├── ActivityDTO.java               - DTO for activities
    ├── LegDTO.java                    - DTO for legs
    ├── NetworkRouteDTO.java          - DTO for network routes
    └── GenericRouteDTO.java          - DTO for generic routes
```

## Usage

### Basic Setup

```java
// Load MATSim configuration
Config config = ConfigUtils.loadConfig("config.xml");

// Configure offload module
OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(
    config, OffloadConfigGroup.class);
offloadConfig.setStoreDirectory("planstore");

// Create and run controller
Controler controler = new Controler(scenario);
controler.addOverridingModule(new OffloadModule());
controler.run();
```

### Configuration Options

```xml
<module name="offload">
    <!-- Directory for plan storage (required) -->
    <param name="storeDirectory" value="/path/to/planstore" />
    
    <!-- Enable monitoring during simulation (default: true) -->
    <param name="enableMobsimMonitoring" value="true" />
    
    <!-- Monitoring interval in seconds (default: 3600.0) -->
    <param name="mobsimMonitoringIntervalSeconds" value="3600.0" />
    
    <!-- Enable dematerialization during simulation (default: true) -->
    <param name="enableMobsimDematerialization" value="true" />
    
    <!-- Enable conversion of regular plans after replanning (default: true) -->
    <param name="enableAfterReplanningDematerialization" value="true" />
</module>
```

## How It Works

### Iteration Lifecycle

#### 1. Iteration Start
```java
// Load all plans as proxies (scores in memory)
OffloadSupport.loadAllPlansAsProxies(person, store);

// Materialize selected plan for simulation
OffloadSupport.ensureSelectedMaterialized(person);
```

#### 2. During Simulation
- Only selected plan is materialized (full route details)
- Plan selectors see all proxies with scores
- Memory footprint: minimal

#### 3. After Replanning
- New regular plans may be created by strategies (ReRoute, TimeAllocationMutator)
- `AfterReplanningDematerializer` detects regular plans
- Persists them to RocksDB
- Converts them to PlanProxy instances
- Dematerializes non-selected plans

#### 4. Iteration End
```java
// Persist all materialized plans
OffloadSupport.persistAllMaterialized(person, store, iteration);
// Plans are automatically dematerialized after persistence
```

### Regular Plan Conversion

During replanning, MATSim strategies may create new regular Plan objects. The module automatically handles this:

```java
// Before: Person has mix of proxies and regular plans
person.getPlans() = [
    PlanProxy (score: 95.2),
    Plan (regular, score: 92.1),  // Created by ReRoute
    PlanProxy (score: 88.5)
]

// After AfterReplanningDematerializer:
person.getPlans() = [
    PlanProxy (score: 95.2),
    PlanProxy (score: 92.1),  // Converted and persisted
    PlanProxy (score: 88.5)
]
```

## Testing

The module includes comprehensive tests:

```bash
# Run all tests
cd OffLoadPlans
mvn test

# Run specific test
mvn test -Dtest=OffloadModuleIT
```

### Test Coverage
- `OffloadModuleIT` - Integration test with full MATSim simulation
- `PlanProxyTest` - Unit tests for proxy lifecycle
- `RocksDbMatsimIntegrationTest` - RocksDB storage tests
- `AfterReplanningDematerializerTest` - Regular plan conversion tests
- `PlanMaterializationMonitorTest` - Statistics collection tests

## Performance Considerations

### When to Use
✅ **Good fit:**
- Large-scale simulations (100K+ agents)
- Multiple plans per agent (3-5+)
- Limited memory available
- Need full plan selection functionality

❌ **Not needed:**
- Small simulations (<10K agents)
- Single plan per agent
- Unlimited memory available
- Custom plan selection that doesn't use scores

### Performance Tips
1. **Storage location**: Use fast SSD for RocksDB storage
2. **Monitoring**: Disable monitoring in production (small overhead)
3. **Memory**: Allocate 1-2 GB heap for proxies + overhead
4. **Parallelization**: RocksDB is thread-safe, scales with cores

## Technical Details

### Serialization
- **Codec**: Apache Fury for high-performance serialization
- **Compression**: LZ4 for storage efficiency
- **Format**: Custom DTOs optimized for MATSim plans

### Storage
- **Database**: RocksDB (embedded key-value store)
- **Key format**: `personId|planId`
- **Value format**: Fury-serialized PlanDTO (compressed)

### Thread Safety
- RocksDB operations are thread-safe
- Proxy materialization uses synchronized access
- No external locking required

## Limitations

1. **Plan modification**: Plans must be materialized before modifying elements
2. **Memory**: Proxies still consume ~100 bytes each (vs 0 with no plans)
3. **Startup overhead**: Initial iteration loads all proxies from disk
4. **Disk space**: RocksDB storage ~50% of uncompressed plan size

## Future Enhancements

Possible improvements (not currently implemented):
- Adaptive dematerialization based on memory pressure
- Parallel plan persistence
- Incremental loading of proxies
- Plan compression in proxy form
- Custom serialization for specific plan types

## Contributing

To contribute:
1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Ensure all tests pass
5. Submit a pull request

## License

See LICENSE file in the repository root.

## Support

For issues, questions, or contributions:
- GitHub Issues: https://github.com/steffenaxer/iteratively-code-examples/issues
- Email: See repository contacts

## References

- MATSim Documentation: https://matsim.org/docs
- RocksDB: https://rocksdb.org/
- Apache Fury: https://fury.apache.org/
