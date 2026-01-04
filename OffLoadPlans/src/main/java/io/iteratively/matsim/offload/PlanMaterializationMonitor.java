package io.iteratively.matsim.offload;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

public final class PlanMaterializationMonitor {
    
    private PlanMaterializationMonitor() {}
    
    /**
     * Collects statistics about plan materialization in the population.
     * 
     * @param population the population to analyze
     * @return a map containing materialization statistics
     */
    public static Map<String, Object> collectStats(Population population) {
        Map<String, Object> stats = new HashMap<>();
        
        int totalPersons = population.getPersons().size();
        int totalPlans = 0;
        int materializedPlans = 0;
        int proxyPlans = 0;
        
        for (Person person : population.getPersons().values()) {
            for (Plan plan : person.getPlans()) {
                totalPlans++;
                if (plan instanceof PlanProxy proxy) {
                    proxyPlans++;
                    if (proxy.isMaterialized()) {
                        materializedPlans++;
                    }
                } else {
                    // Regular plans are considered materialized
                    materializedPlans++;
                }
            }
        }
        
        stats.put("totalPersons", totalPersons);
        stats.put("totalPlans", totalPlans);
        stats.put("materializedPlans", materializedPlans);
        stats.put("proxyPlans", proxyPlans);
        stats.put("unmaterializedProxies", proxyPlans - materializedPlans);
        
        return stats;
    }
}
