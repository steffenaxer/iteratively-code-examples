package io.iteratively.matsim.offload;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Monitors and tracks materialized plans across the population.
 * Provides statistics for debugging and analysis of memory usage.
 */
public final class PlanMaterializationMonitor {
    private static final Logger log = LogManager.getLogger(PlanMaterializationMonitor.class);

    /**
     * Statistics about materialized plans.
     */
    public record MaterializationStats(
            int totalPersons,
            int totalPlans,
            int materializedPlans,
            int selectedMaterializedPlans,
            int nonSelectedMaterializedPlans,
            Map<String, Integer> personsWithNMaterializedPlans,
            long maxMaterializationDurationMs,
            double avgMaterializationDurationMs
    ) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("MaterializationStats{");
            sb.append("totalPersons=").append(totalPersons);
            sb.append(", totalPlans=").append(totalPlans);
            sb.append(", materializedPlans=").append(materializedPlans);
            sb.append(", selectedMaterialized=").append(selectedMaterializedPlans);
            sb.append(", nonSelectedMaterialized=").append(nonSelectedMaterializedPlans);
            if (maxMaterializationDurationMs > 0) {
                sb.append(", maxDuration=").append(maxMaterializationDurationMs).append("ms");
                sb.append(", avgDuration=").append(String.format("%.1f", avgMaterializationDurationMs)).append("ms");
            }
            sb.append(", distribution=").append(personsWithNMaterializedPlans);
            sb.append('}');
            return sb.toString();
        }
    }

    private PlanMaterializationMonitor() {}

    /**
     * Collects statistics about materialized plans in the population.
     *
     * @param population the population to analyze
     * @return statistics about materialized plans
     */
    public static MaterializationStats collectStats(Population population) {
        int totalPersons = 0;
        int totalPlans = 0;
        int materializedPlans = 0;
        int selectedMaterializedPlans = 0;
        int nonSelectedMaterializedPlans = 0;
        Map<String, Integer> distribution = new HashMap<>();
        long maxDuration = 0;
        long totalDuration = 0;
        int countForAvg = 0;

        for (Person person : population.getPersons().values()) {
            totalPersons++;
            Plan selectedPlan = person.getSelectedPlan();
            int materializedCount = 0;

            for (Plan plan : person.getPlans()) {
                totalPlans++;
                if (plan instanceof PlanProxy proxy) {
                    if (proxy.isMaterialized()) {
                        materializedPlans++;
                        materializedCount++;
                        if (plan == selectedPlan) {
                            selectedMaterializedPlans++;
                        } else {
                            nonSelectedMaterializedPlans++;
                        }
                        
                        // Track duration
                        long duration = proxy.getMaterializationDurationMs();
                        if (duration > 0) {
                            maxDuration = Math.max(maxDuration, duration);
                            totalDuration += duration;
                            countForAvg++;
                        }
                    }
                } else {
                    // Regular plans are always "materialized"
                    materializedPlans++;
                    materializedCount++;
                    if (plan == selectedPlan) {
                        selectedMaterializedPlans++;
                    } else {
                        nonSelectedMaterializedPlans++;
                    }
                }
            }

            String key = materializedCount + " materialized";
            distribution.put(key, distribution.getOrDefault(key, 0) + 1);
        }

        double avgDuration = countForAvg > 0 ? (double) totalDuration / countForAvg : 0;

        return new MaterializationStats(
                totalPersons,
                totalPlans,
                materializedPlans,
                selectedMaterializedPlans,
                nonSelectedMaterializedPlans,
                distribution,
                maxDuration,
                avgDuration
        );
    }

    /**
     * Logs statistics about materialized plans.
     *
     * @param population the population to analyze
     * @param phase description of when this is being logged (e.g., "iteration start", "iteration end")
     */
    public static void logStats(Population population, String phase) {
        MaterializationStats stats = collectStats(population);
        log.info("Plan materialization stats at {}: {}", phase, stats);
        
        // Log memory efficiency
        if (stats.totalPlans > 0) {
            double materializationRate = (stats.materializedPlans * 100.0) / stats.totalPlans;
            log.info("Materialization rate: {}/{} ({:.2f}%)", 
                    stats.materializedPlans, stats.totalPlans, materializationRate);
        }
        
        // Log duration info
        if (stats.maxMaterializationDurationMs > 0) {
            log.info("Materialization durations - max: {}ms, avg: {:.1f}ms", 
                    stats.maxMaterializationDurationMs, stats.avgMaterializationDurationMs);
        }
        
        // Warn if too many non-selected plans are materialized
        if (stats.nonSelectedMaterializedPlans > 0) {
            log.warn("Found {} non-selected materialized plans - consider enabling auto-dematerialization", 
                    stats.nonSelectedMaterializedPlans);
        }
    }

    /**
     * Dematerializes plans that have been materialized for longer than the specified duration.
     *
     * @param person the person whose plans to check
     * @param maxDurationMs maximum duration in milliseconds a non-selected plan can remain materialized
     * @return the number of plans that were dematerialized
     */
    public static int dematerializeOldNonSelected(Person person, long maxDurationMs) {
        Plan selectedPlan = person.getSelectedPlan();
        int dematerialized = 0;

        for (Plan plan : person.getPlans()) {
            if (plan != selectedPlan && plan instanceof PlanProxy proxy) {
                if (proxy.isMaterialized()) {
                    long duration = proxy.getMaterializationDurationMs();
                    if (duration > maxDurationMs) {
                        proxy.dematerialize();
                        dematerialized++;
                    }
                }
            }
        }

        return dematerialized;
    }

    /**
     * Dematerializes all non-selected plans across the population that have been
     * materialized for longer than the specified duration.
     *
     * @param population the population to process
     * @param maxDurationMs maximum duration in milliseconds a non-selected plan can remain materialized
     * @return the total number of plans that were dematerialized
     */
    public static int dematerializeAllOldNonSelected(Population population, long maxDurationMs) {
        int totalDematerialized = 0;

        for (Person person : population.getPersons().values()) {
            totalDematerialized += dematerializeOldNonSelected(person, maxDurationMs);
        }

        if (totalDematerialized > 0) {
            log.info("Auto-dematerialized {} non-selected plans older than {}ms", 
                    totalDematerialized, maxDurationMs);
        }

        return totalDematerialized;
    }

    /**
     * Dematerializes all non-selected plans for a person.
     *
     * @param person the person whose plans to dematerialize
     * @return the number of plans that were dematerialized
     */
    public static int dematerializeNonSelected(Person person) {
        Plan selectedPlan = person.getSelectedPlan();
        int dematerialized = 0;

        for (Plan plan : person.getPlans()) {
            if (plan != selectedPlan && plan instanceof PlanProxy proxy) {
                if (proxy.isMaterialized()) {
                    proxy.dematerialize();
                    dematerialized++;
                }
            }
        }

        return dematerialized;
    }

    /**
     * Dematerializes all non-selected plans across the entire population.
     *
     * @param population the population to process
     * @return the total number of plans that were dematerialized
     */
    public static int dematerializeAllNonSelected(Population population) {
        int totalDematerialized = 0;

        for (Person person : population.getPersons().values()) {
            totalDematerialized += dematerializeNonSelected(person);
        }

        if (totalDematerialized > 0) {
            log.info("Auto-dematerialized {} non-selected plans", totalDematerialized);
        }

        return totalDematerialized;
    }
}
