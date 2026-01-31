package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;

/**
 * Dematerializes non-selected plans after replanning.
 * 
 * During replanning (which happens after mobsim), multiple plans may be materialized
 * for selection, mutation, or evaluation. This listener ensures that only the selected
 * plan remains materialized after replanning completes, reducing memory footprint.
 */
public final class AfterReplanningDematerializer implements AfterMobsimListener {
    private static final Logger log = LogManager.getLogger(AfterReplanningDematerializer.class);
    
    private final Scenario scenario;
    private final OffloadConfigGroup config;
    
    @Inject
    public AfterReplanningDematerializer(Scenario scenario) {
        this.scenario = scenario;
        this.config = ConfigUtils.addOrGetModule(scenario.getConfig(), OffloadConfigGroup.class);
    }
    
    @Override
    public void notifyAfterMobsim(AfterMobsimEvent event) {
        // Only dematerialize if enabled in config
        if (!config.getEnableAfterReplanningDematerialization()) {
            return;
        }
        
        int dematerialized = 0;
        var population = scenario.getPopulation();
        
        for (Person person : population.getPersons().values()) {
            Plan selectedPlan = person.getSelectedPlan();
            
            for (Plan plan : person.getPlans()) {
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
            log.info("After replanning: Dematerialized {} non-selected plans", dematerialized);
        }
    }
}
