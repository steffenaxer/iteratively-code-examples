package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonScoreEvent;
import org.matsim.api.core.v01.events.handler.PersonScoreEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.ReplanningEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.ReplanningListener;

/**
 * Actively dematerializes non-selected plans at various points during the iteration
 * to ensure that only the selected plan remains materialized for extended periods.
 * 
 * This provides an "agile" dematerialization strategy that prevents non-selected plans
 * from staying materialized longer than necessary.
 */
public final class ActiveDematerializationListener implements 
        BeforeMobsimListener, AfterMobsimListener, ReplanningListener, PersonScoreEventHandler {
    
    private static final Logger log = LogManager.getLogger(ActiveDematerializationListener.class);
    
    private final Scenario scenario;
    
    @Inject
    public ActiveDematerializationListener(Scenario scenario) {
        this.scenario = scenario;
    }
    
    private OffloadConfigGroup getConfig() {
        return ConfigUtils.addOrGetModule(scenario.getConfig(), OffloadConfigGroup.class);
    }
    
    @Override
    public void notifyBeforeMobsim(BeforeMobsimEvent event) {
        if (!getConfig().isEnableAutodematerialization()) {
            return;
        }
        
        // Before mobsim: dematerialize old non-selected plans based on max lifetime
        dematerializeOldNonSelected(event, "before mobsim");
    }
    
    @Override
    public void notifyAfterMobsim(AfterMobsimEvent event) {
        if (!getConfig().isEnableAutodematerialization()) {
            return;
        }
        
        // After mobsim: dematerialize old non-selected plans
        dematerializeOldNonSelected(event, "after mobsim");
    }
    
    @Override
    public void notifyReplanning(ReplanningEvent event) {
        if (!getConfig().isEnableAutodematerialization()) {
            return;
        }
        
        // After replanning: dematerialize old non-selected plans
        dematerializeOldNonSelected(event, "after replanning");
    }
    
    @Override
    public void handleEvent(PersonScoreEvent event) {
        // This handler is called frequently during scoring
        // We don't dematerialize here as it would be too aggressive
        // and could impact performance. The other hooks are sufficient.
    }
    
    private void dematerializeOldNonSelected(Object event, String phase) {
        int iteration = -1;
        var population = getPopulationFromEvent(event);
        long maxLifetimeMs = getConfig().getMaxNonSelectedMaterializationTimeMs();
        
        if (event instanceof BeforeMobsimEvent e) {
            iteration = e.getIteration();
        } else if (event instanceof AfterMobsimEvent e) {
            iteration = e.getIteration();
        } else if (event instanceof ReplanningEvent e) {
            iteration = e.getIteration();
        }
        
        int dematerialized = 0;
        int totalNonSelectedMaterialized = 0;
        long maxDuration = 0;
        
        for (Person person : population.getPersons().values()) {
            Plan selectedPlan = person.getSelectedPlan();
            
            for (Plan plan : person.getPlans()) {
                if (plan != selectedPlan && plan instanceof PlanProxy proxy) {
                    if (proxy.isMaterialized()) {
                        totalNonSelectedMaterialized++;
                        long duration = proxy.getMaterializationDurationMs();
                        maxDuration = Math.max(maxDuration, duration);
                        
                        // Dematerialize if exceeds max lifetime
                        if (duration > maxLifetimeMs) {
                            proxy.dematerialize();
                            dematerialized++;
                        }
                    }
                }
            }
        }
        
        if (dematerialized > 0 && getConfig().isLogMaterializationStats()) {
            log.info("Iteration {}, {}: Dematerialized {} non-selected plans older than {}ms " +
                    "(found {} materialized, max age: {}ms)", 
                    iteration, phase, dematerialized, maxLifetimeMs, 
                    totalNonSelectedMaterialized, maxDuration);
        } else if (totalNonSelectedMaterialized > 0 && getConfig().isLogMaterializationStats()) {
            log.debug("Iteration {}, {}: {} non-selected plans materialized (max age: {}ms, threshold: {}ms)",
                    iteration, phase, totalNonSelectedMaterialized, maxDuration, maxLifetimeMs);
        }
        
        // Log statistics if enabled and plans were dematerialized
        if (getConfig().isLogMaterializationStats() && dematerialized > 0) {
            PlanMaterializationMonitor.logStats(population, 
                    String.format("iteration %d, %s (after time-based dematerialization)", iteration, phase));
        }
    }
    
    private org.matsim.api.core.v01.population.Population getPopulationFromEvent(Object event) {
        if (event instanceof BeforeMobsimEvent e) {
            return e.getServices().getScenario().getPopulation();
        } else if (event instanceof AfterMobsimEvent e) {
            return e.getServices().getScenario().getPopulation();
        } else if (event instanceof ReplanningEvent e) {
            return e.getServices().getScenario().getPopulation();
        }
        throw new IllegalArgumentException("Unsupported event type: " + event.getClass());
    }
    
    @Override
    public void reset(int iteration) {
        // Reset is called at the beginning of each iteration
        // No action needed here as we handle dematerialization in other hooks
    }
}
