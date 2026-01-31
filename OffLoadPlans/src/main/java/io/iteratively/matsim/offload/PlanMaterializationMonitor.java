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
        int materializedProxyPlans = 0;
        int proxyPlans = 0;
        int regularPlans = 0;
        
        for (Person person : population.getPersons().values()) {
            for (Plan plan : person.getPlans()) {
                totalPlans++;
                if (plan instanceof PlanProxy proxy) {
                    proxyPlans++;
                    if (proxy.isMaterialized()) {
                        materializedProxyPlans++;
                    }
                } else {
                    // Count regular (non-proxy) plans separately
                    regularPlans++;
                }
            }
        }
        
        // Calculate materialization rate only for proxy plans
        // This represents how many proxy plans are currently loaded in memory
        double materializationRate = proxyPlans > 0 
            ? (100.0 * materializedProxyPlans / proxyPlans) 
            : 0.0;
        
        stats.put("totalPersons", totalPersons);
        stats.put("totalPlans", totalPlans);
        stats.put("materializedPlans", materializedProxyPlans);
        stats.put("proxyPlans", proxyPlans);
        stats.put("regularPlans", regularPlans);
        stats.put("materializationRate", String.format("%.2f%%", materializationRate));
        
        return stats;
    }
}
