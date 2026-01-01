# Technical Implementation Notes

## Multi-Plan Proxy Architecture

This document provides technical details about the proxy architecture implementation for developers who want to understand or extend the code.

## Core Design Decisions

### Why Proxies Instead of Full Plans?

**Memory Consumption:**
```
Regular Plan object:
- Activities: ~100 bytes each × 5-10 activities = 500-1000 bytes
- Legs: ~150 bytes each × 4-9 legs = 600-1350 bytes  
- Routes: ~200-500 bytes each
- Attributes and metadata: ~200 bytes
Total: ~2-3 KB per plan × 3 plans = 6-9 KB per agent

PlanProxy object:
- PlanHeader reference: 8 bytes
- PlanHeader (score, type, metadata): ~64 bytes
- Store reference: 8 bytes
- Person reference: 8 bytes
Total: ~88 bytes × 3 plans = ~264 bytes per agent

Savings: ~96-97% memory reduction
```

### Lazy Materialization Strategy

The proxy only materializes when:
1. Plan elements are accessed via `getPlanElements()`
2. Activities or legs are added via `addActivity()` or `addLeg()`
3. Attributes are accessed via `getAttributes()`
4. Person is set/changed via `setPerson()`

The proxy does NOT materialize when:
- Score is read via `getScore()` → reads from header
- Score is set via `setScore()` → updates header + store
- Type is read via `getType()` → reads from header
- Plan ID is accessed → reads from header

This means plan selectors (ChangeExpBeta, etc.) that only look at scores can work entirely on proxies without any materialization.

## Implementation Details

### PlanHeader Mutability

`PlanHeader.score` is intentionally mutable because:
1. MATSim's scoring updates scores frequently
2. Creating new PlanHeader objects for each score update would create GC pressure
3. The score is synchronized to the store via `store.updateScore()` immediately

Trade-off: Mutable state simplifies implementation at the cost of thread-safety. Since MATSim is single-threaded during iteration, this is acceptable.

### Store Update Strategy

**Score updates:**
```java
proxy.setScore(5.0) 
  → header.score = 5.0 (immediate)
  → store.updateScore(personId, planId, 5.0, iter) (immediate)
```

**Plan content updates:**
```java
proxy.addActivity(activity)
  → materializeIfNeeded() (lazy, first time only)
  → materializedPlan.addActivity(activity) (delegated)
  → persisted at iteration end via persistAllMaterialized()
```

This ensures:
- Scores are always up-to-date in the store (needed for next iteration)
- Plan content is only written when actually modified
- MapDB writes are minimized (performance)

### Handling Mixed Plan Types

During an iteration, a person might have:
- Old plans loaded as proxies
- New plans created by replanning (regular Plan objects)

`persistAllMaterialized()` handles both:

```java
for (Plan plan : person.getPlans()) {
    if (plan instanceof PlanProxy proxy) {
        // Handle proxy logic
        if (proxy.isMaterialized()) {
            persistPlan(proxy.getMaterializedPlan());
        } else {
            updateScore(proxy.getPlanId(), proxy.getScore());
        }
    } else {
        // Handle regular plan
        persistPlan(plan);
    }
}
```

This makes the architecture robust to MATSim's replanning system.

## Integration Points

### 1. Iteration Lifecycle

**Start of Iteration:**
```
OffloadIterationHooks.notifyIterationStarts()
  → for each person:
      loadAllPlansAsProxies(person, store, iter)
        → listPlanHeaders(personId) from store
        → create PlanProxy for each header
        → person.getPlans().clear()
        → person.addPlan(proxy) for each
        → person.setSelectedPlan(activeProxy)
  → store.commit()
```

**End of Iteration:**
```
OffloadIterationHooks.notifyIterationEnds()
  → for each person:
      persistAllMaterialized(person, store, iter)
        → for each plan:
            if proxy & materialized: putPlan(materialized)
            if proxy & not materialized: updateScore()
            if not proxy: putPlan(plan)
        → setActivePlanId(selectedPlanId)
        → person.getPlans().clear()
  → store.commit()
  → cache.evictAll()
```

### 2. Plan Selection

**For Removal (during replanning):**
```
OffloadModule.OffloadPlanSelectorProvider
  → wraps delegate selector (e.g., WorstPlanSelector)
  → LazyOffloadPlanSelector
      → loads headers from store
      → creates temporary proxies
      → delegates to wrapped selector
      → returns selected proxy
      → MATSim removes the selected plan
```

**For Execution (ChangeExpBeta):**
```
MATSim's replanning system
  → iterates over person.getPlans() (all proxies)
  → reads plan.getScore() (no materialization)
  → selects plan based on exp-beta
  → person.setSelectedPlan(selectedProxy)
```

### 3. Plan Execution

**During Mobility Simulation:**
```
MATSim's QSim
  → loads person.getSelectedPlan()
  → calls plan.getPlanElements() (triggers materialization!)
  → executes activities and legs
  → updates score at end
```

This is the only time full materialization happens, and only for the selected plan.

## Performance Characteristics

### Time Complexity

| Operation | Without Proxies | With Proxies |
|-----------|----------------|--------------|
| Load all plans at iteration start | O(n × p × e) | O(n × p) |
| Plan selection (ChangeExpBeta) | O(n × p) | O(n × p) |
| Execute selected plan | O(e) | O(e) + first-time materialization |
| Save all plans at iteration end | O(n × p × e) | O(n × m × e) |

Where:
- n = number of persons
- p = plans per person (~3)
- e = elements per plan (~10-20)
- m = materialized plans per person (~1)

**Key insight:** Materialization overhead is amortized over the iteration since it only happens once per plan that's actually used.

### MapDB Write Patterns

1. **Iteration Start:** Read-only (load headers)
2. **During Iteration:** Minimal writes (score updates)
3. **Iteration End:** Bulk writes (materialized plans)

This pattern plays well with MapDB's async executor and write buffering.

## Extension Points

### Adding Custom Plan Metadata

To add custom metadata that should be available without materialization:

1. Extend `PlanHeader` with new field
2. Update `MapDbPlanStore.listPlanHeaders()` to populate it
3. Update `PlanProxy` to expose it via a getter
4. Update `persistAllMaterialized()` if it needs to be persisted

Example:
```java
// In PlanHeader
public final class PlanHeader {
    // ... existing fields ...
    public final String customTag;  // Add new field
}

// In PlanProxy
public String getCustomTag() {
    return header.customTag;  // No materialization needed
}
```

### Custom Materialization Triggers

If you need to materialize based on custom logic:

```java
// In PlanProxy
public void materializeIfCondition(boolean condition) {
    if (condition && materializedPlan == null) {
        materializeIfNeeded();
    }
}
```

## Testing Strategy

### Unit Tests

Focus on:
1. Proxy delegation correctness
2. Lazy materialization triggers
3. Score update synchronization
4. Mixed plan type handling

### Integration Tests

Focus on:
1. Full MATSim lifecycle (already in `OffloadModuleIT`)
2. Memory consumption measurement
3. Performance benchmarking
4. Compatibility with different selectors

### Edge Cases to Test

1. Person with no plans
2. Person with only new (non-proxy) plans
3. All plans materialized
4. No plans materialized
5. Plan limit enforcement with proxies
6. Concurrent access (if multi-threading is added)

## Known Limitations

1. **Thread-safety:** PlanHeader is mutable, not thread-safe
   - Acceptable because MATSim iterations are single-threaded
   - Would need synchronization for parallel iteration processing

2. **Memory overhead of proxy objects:** ~88 bytes per proxy
   - Still 97% better than full plans
   - Could be reduced further with pooling/flyweight pattern

3. **First-access latency:** Materialization has overhead
   - Only happens once per selected plan per iteration
   - Could be mitigated with predictive pre-loading

4. **MapDB transaction overhead:** Each score update is a write
   - Mitigated by async executor and write buffering
   - Could batch score updates if latency becomes an issue

## Future Optimizations

1. **Proxy pooling:** Reuse proxy objects across iterations
2. **Predictive materialization:** Pre-load likely-to-be-selected plans
3. **Batch score updates:** Buffer score changes and write in bulk
4. **Compressed headers:** Pack header data more efficiently
5. **Parallel loading:** Load proxies for different persons in parallel

## References

- MATSim Documentation: https://www.matsim.org/
- MapDB Documentation: https://mapdb.org/
- Apache Fury Serialization: https://fury.apache.org/
