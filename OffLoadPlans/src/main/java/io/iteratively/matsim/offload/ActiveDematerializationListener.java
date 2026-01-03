package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.events.PersonScoreEvent;
import org.matsim.api.core.v01.events.handler.PersonScoreEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
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
    
    private final OffloadConfigGroup config;
    
    @Inject
    public ActiveDematerializationListener(OffloadConfigGroup config) {
        this.config = config;
    }
    
    @Override
    public void notifyBeforeMobsim(BeforeMobsimEvent event) {
        if (!config.isEnableAutodematerialization()) {
            return;
        }
        
        // Before mobsim: ensure only selected plans are materialized
        dematerializeAllNonSelected(event, "before mobsim");
    }
    
    @Override
    public void notifyAfterMobsim(AfterMobsimEvent event) {
        if (!config.isEnableAutodematerialization()) {
            return;
        }
        
        // After mobsim: dematerialize all non-selected plans
        // (selected plans may have been modified during simulation)
        dematerializeAllNonSelected(event, "after mobsim");
    }
    
    @Override
    public void notifyReplanning(ReplanningEvent event) {
        if (!config.isEnableAutodematerialization()) {
            return;
        }
        
        // After replanning: dematerialize all non-selected plans
        // During replanning, some plans may have been temporarily materialized
        // for plan selection or mutation
        dematerializeAllNonSelected(event, "after replanning");
    }
    
    @Override
    public void handleEvent(PersonScoreEvent event) {
        // This handler is called frequently during scoring
        // We don't dematerialize here as it would be too aggressive
        // and could impact performance. The other hooks are sufficient.
    }
    
    private void dematerializeAllNonSelected(Object event, String phase) {
        int iteration = -1;
        var population = getPopulationFromEvent(event);
        
        if (event instanceof BeforeMobsimEvent e) {
            iteration = e.getIteration();
        } else if (event instanceof AfterMobsimEvent e) {
            iteration = e.getIteration();
        } else if (event instanceof ReplanningEvent e) {
            iteration = e.getIteration();
        }
        
        int dematerialized = 0;
        int totalNonSelectedMaterialized = 0;
        
        for (Person person : population.getPersons().values()) {
            Plan selectedPlan = person.getSelectedPlan();
            
            for (Plan plan : person.getPlans()) {
                if (plan != selectedPlan && plan instanceof PlanProxy proxy) {
                    if (proxy.isMaterialized()) {
                        totalNonSelectedMaterialized++;
                        proxy.dematerialize();
                        dematerialized++;
                    }
                }
            }
        }
        
        if (dematerialized > 0 && config.isLogMaterializationStats()) {
            log.info("Iteration {}, {}: Dematerialized {} non-selected plans (found {} materialized)", 
                    iteration, phase, dematerialized, totalNonSelectedMaterialized);
        }
        
        // Log statistics if enabled
        if (config.isLogMaterializationStats() && totalNonSelectedMaterialized > 0) {
            PlanMaterializationMonitor.logStats(population, 
                    String.format("iteration %d, %s (after auto-dematerialization)", iteration, phase));
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
