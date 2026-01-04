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
 * OffloadConfigGroup. Default is 3600 seconds (1 hour).</p>
 * 
 * <p>Statistics are logged as a single JSON object with essential metrics.</p>
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
        
        // Collect statistics
        PlanMaterializationMonitor.MaterializationStats stats = 
                PlanMaterializationMonitor.collectStats(population);
        
        // Calculate materialization rate
        double materializationRate = stats.totalPlans() > 0 
                ? (stats.materializedPlans() * 100.0) / stats.totalPlans() 
                : 0.0;
        
        // Log as JSON object with essential metrics
        log.info("{{\"time\":\"{}\", \"totalPersons\":{}, \"totalPlans\":{}, \"materializedPlans\":{}, " +
                "\"selectedMaterialized\":{}, \"nonSelectedMaterialized\":{}, \"materializationRate\":{}}}", 
                formatTime(simTime),
                stats.totalPersons(),
                stats.totalPlans(),
                stats.materializedPlans(),
                stats.selectedMaterializedPlans(),
                stats.nonSelectedMaterializedPlans(),
                String.format("%.2f", materializationRate));
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
