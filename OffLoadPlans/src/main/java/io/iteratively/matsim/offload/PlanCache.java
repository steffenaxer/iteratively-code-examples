package io.iteratively.matsim.offload;

import org.matsim.api.core.v01.population.Plan;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class PlanCache {
    private static final class Key {
        private final String personId;
        private final String planId;

        Key(String personId, String planId) {
            this.personId = personId;
            this.planId = planId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(personId, key.personId) && Objects.equals(planId, key.planId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(personId, planId);
        }
    }

    private final int maxEntries;
    private final PlanStore store;
    private final ConcurrentHashMap<Key, Plan> cache = new ConcurrentHashMap<>();
    private final LinkedHashMap<Key, Boolean> lru = new LinkedHashMap<>(16, 0.75f, true);

    public PlanCache(PlanStore store, int maxEntries) {
        this.store = store;
        this.maxEntries = Math.max(8, maxEntries);
    }

    public Plan materialize(String personId, String planId) {
        var key = new Key(personId, planId);
        var p = cache.get(key);
        if (p != null) return p;
        p = store.materialize(personId, planId);
        synchronized (lru) {
            cache.put(key, p);
            lru.put(key, Boolean.TRUE);
            if (lru.size() > maxEntries) {
                var it = lru.entrySet().iterator();
                if (it.hasNext()) {
                    Map.Entry<Key, Boolean> eldest = it.next();
                    it.remove();
                    cache.remove(eldest.getKey());
                }
            }
        }
        return p;
    }

    public void evictAll() {
        synchronized (lru) { lru.clear(); cache.clear(); }
    }
}
