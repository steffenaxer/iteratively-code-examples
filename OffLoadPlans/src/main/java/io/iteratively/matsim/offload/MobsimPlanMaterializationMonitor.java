package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;

/**
 * Monitors plan materialization during the MobSim (mobility simulation) phase.
 * 
 * <p>This listener tracks materialized plans during the simulation, which is important because
 * plans can be materialized during WithinDayReplanning or other MobSim operations. Regular
 * monitoring helps understand memory usage patterns and the effectiveness of the offloading strategy.</p>
 * 
 * <p>The monitoring interval is configurable via {@code mobsimMonitoringIntervalSeconds} in the
 * OffloadConfigGroup. A reasonable default is 300 seconds (5 minutes) to balance between
 * monitoring granularity and performance overhead.</p>
 * 
 * <p>Statistics logged include:</p>
 * <ul>
 *   <li>Total number of plans vs. materialized plans</li>
 *   <li>Materialization rate (percentage)</li>
 *   <li>Selected vs. non-selected materialized plans</li>
 *   <li>Materialization duration statistics (max and average)</li>
 *   <li>Distribution of materialized plans across the population</li>
 * </ul>
 */
public final class MobsimPlanMaterializationMonitor implements MobsimBeforeSimStepListener {
    private static final Logger log = LogManager.getLogger(MobsimPlanMaterializationMonitor.class);
    
    private final Scenario scenario;
    private double lastMonitoringTime = -1.0;
    
    @Inject
    public MobsimPlanMaterializationMonitor(Scenario scenario) {
        this.scenario = scenario;
    }
    
    private OffloadConfigGroup getConfig() {
        return ConfigUtils.addOrGetModule(scenario.getConfig(), OffloadConfigGroup.class);
    }
    
    @Override
    public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent event) {
        if (!getConfig().isEnableMobsimMonitoring() || !getConfig().isLogMaterializationStats()) {
            return;
        }
        
        double currentTime = event.getSimulationTime();
        double monitoringInterval = getConfig().getMobsimMonitoringIntervalSeconds();
        
        // Initialize on first call
        if (lastMonitoringTime < 0) {
            lastMonitoringTime = currentTime;
            return;
        }
        
        // Check if it's time to monitor
        if (currentTime - lastMonitoringTime >= monitoringInterval) {
            monitorMaterialization(currentTime);
            lastMonitoringTime = currentTime;
        }
    }
    
    private void monitorMaterialization(double simTime) {
        Population population = scenario.getPopulation();
        
        // Collect and log statistics
        PlanMaterializationMonitor.MaterializationStats stats = 
                PlanMaterializationMonitor.collectStats(population);
        
        log.info("MobSim plan materialization stats at t={}s: {}", 
                formatTime(simTime), stats);
        
        // Log memory efficiency
        if (stats.totalPlans() > 0) {
            double materializationRate = (stats.materializedPlans() * 100.0) / stats.totalPlans();
            log.info("MobSim materialization rate at t={}s: {}/{} ({} %)", 
                    formatTime(simTime),
                    stats.materializedPlans(), 
                    stats.totalPlans(), 
                    String.format("%.2f", materializationRate));
        }
        
        // Log duration info
        if (stats.maxMaterializationDurationMs() > 0) {
            log.info("MobSim materialization durations at t={}s - max: {}ms, avg: {}ms", 
                    formatTime(simTime),
                    stats.maxMaterializationDurationMs(), 
                    String.format("%.1f", stats.avgMaterializationDurationMs()));
        }
        
        // Warn if too many non-selected plans are materialized
        if (stats.nonSelectedMaterializedPlans() > 0) {
            double nonSelectedRate = (stats.nonSelectedMaterializedPlans() * 100.0) / stats.materializedPlans();
            log.warn("MobSim at t={}s: Found {} non-selected materialized plans ({} % of materialized) - " +
                    "consider enabling auto-dematerialization or reducing maxNonSelectedMaterializationTimeMs", 
                    formatTime(simTime),
                    stats.nonSelectedMaterializedPlans(),
                    String.format("%.1f", nonSelectedRate));
        }
    }
    
    /**
     * Formats simulation time for logging (converts seconds to hours:minutes:seconds format).
     */
    private String formatTime(double seconds) {
        int hours = (int) (seconds / 3600);
        int minutes = (int) ((seconds % 3600) / 60);
        int secs = (int) (seconds % 60);
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
}
