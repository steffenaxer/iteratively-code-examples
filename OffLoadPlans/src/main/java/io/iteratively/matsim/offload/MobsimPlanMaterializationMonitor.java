package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;

import java.util.Map;

/**
 * Monitors plan materialization during MobSim at configurable intervals.
 * Logs statistics as JSON objects with time-formatted simulation time.
 */
public final class MobsimPlanMaterializationMonitor implements MobsimBeforeSimStepListener {
    private static final Logger log = LogManager.getLogger(MobsimPlanMaterializationMonitor.class);
    
    private final Scenario scenario;
    private final OffloadConfigGroup config;
    private double nextMonitoringTime = 0.0;
    
    @Inject
    public MobsimPlanMaterializationMonitor(Scenario scenario) {
        this.scenario = scenario;
        this.config = ConfigUtils.addOrGetModule(scenario.getConfig(), OffloadConfigGroup.class);
        
        // Set first monitoring time to the interval (so we don't log at time 0)
        if (config.getEnableMobsimMonitoring()) {
            this.nextMonitoringTime = config.getMobsimMonitoringIntervalSeconds();
        }
    }
    
    @Override
    public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent event) {
        // Early return if monitoring is disabled
        if (!config.getEnableMobsimMonitoring()) {
            return;
        }
        
        double currentTime = event.getSimulationTime();
        
        // Check if it's time to monitor
        if (currentTime >= nextMonitoringTime) {
            logMaterializationStats(currentTime);
            
            // Schedule next monitoring time
            nextMonitoringTime += config.getMobsimMonitoringIntervalSeconds();
        }
    }
    
    private void logMaterializationStats(double simulationTime) {
        // First, dematerialize non-selected plans to reduce memory footprint (if enabled)
        if (config.getEnableMobsimDematerialization()) {
            dematerializeNonSelectedPlans();
        }
        
        // Then collect and log stats
        Map<String, Object> stats = PlanMaterializationMonitor.collectStats(scenario.getPopulation());
        
        // Create JSON object with time-formatted output
        JSONObject json = new JSONObject();
        json.put("time", formatTime(simulationTime));
        json.put("totalPersons", stats.get("totalPersons"));
        json.put("totalPlans", stats.get("totalPlans"));
        json.put("proxyPlans", stats.get("proxyPlans"));
        json.put("materializedPlans", stats.get("materializedPlans"));
        json.put("regularPlans", stats.get("regularPlans"));
        json.put("materializationRate", stats.get("materializationRate"));
        
        log.info(json.toString());
    }
    
    /**
     * Dematerializes all non-selected plans to reduce memory footprint during simulation.
     * Only the selected plan should remain materialized for each person.
     */
    private void dematerializeNonSelectedPlans() {
        int dematerialized = 0;
        
        for (var person : scenario.getPopulation().getPersons().values()) {
            var selectedPlan = person.getSelectedPlan();
            
            for (var plan : person.getPlans()) {
                if (plan instanceof PlanProxy proxy) {
                    // Only dematerialize if this is not the selected plan and it's currently materialized
                    if (plan != selectedPlan && proxy.isMaterialized()) {
                        proxy.dematerialize();
                        dematerialized++;
                    }
                }
            }
        }
        
        if (dematerialized > 0) {
            log.debug("Dematerialized {} non-selected plans", dematerialized);
        }
    }
    
    /**
     * Formats simulation time in seconds to HH:MM:SS format.
     */
    private String formatTime(double seconds) {
        int totalSeconds = (int) seconds;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int secs = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
}
