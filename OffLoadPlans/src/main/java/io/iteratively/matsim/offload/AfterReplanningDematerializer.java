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

import java.util.ArrayList;
import java.util.List;

/**
 * Dematerializes non-selected plans and converts regular plans to proxies after replanning.
 * 
 * During replanning (which happens after mobsim), multiple plans may be materialized
 * for selection, mutation, or evaluation. Additionally, new regular (non-proxy) plans
 * may be created during replanning. This listener:
 * 1. Converts any regular plans to PlanProxy instances (persisting them first)
 * 2. Dematerializes non-selected proxy plans to reduce memory footprint
 */
public final class AfterReplanningDematerializer implements AfterMobsimListener {
    private static final Logger log = LogManager.getLogger(AfterReplanningDematerializer.class);
    
    private final Scenario scenario;
    private final OffloadConfigGroup config;
    private final PlanStore store;
    
    @Inject
    public AfterReplanningDematerializer(Scenario scenario, PlanStore store) {
        this.scenario = scenario;
        this.config = ConfigUtils.addOrGetModule(scenario.getConfig(), OffloadConfigGroup.class);
        this.store = store;
    }
    
    @Override
    public void notifyAfterMobsim(AfterMobsimEvent event) {
        // Only process if enabled in config
        if (!config.getEnableAfterReplanningDematerialization()) {
            return;
        }
        
        int iter = event.getIteration();
        int regularPlansConverted = 0;
        int dematerialized = 0;
        var population = scenario.getPopulation();
        
        for (Person person : population.getPersons().values()) {
            Plan selectedPlan = person.getSelectedPlan();
            
            // First pass: identify and convert regular plans to proxies
            List<Plan> regularPlans = new ArrayList<>();
            for (Plan plan : person.getPlans()) {
                if (!(plan instanceof PlanProxy)) {
                    regularPlans.add(plan);
                }
            }
            
            // Convert regular plans to proxies while maintaining order
            // We need to rebuild the plan list to maintain indices
            List<Plan> allPlans = new ArrayList<>(person.getPlans());
            for (int i = 0; i < allPlans.size(); i++) {
                Plan plan = allPlans.get(i);
                if (!(plan instanceof PlanProxy)) {
                    // This is a regular plan that needs to be converted
                    String personId = person.getId().toString();
                    String planId = OffloadSupport.ensurePlanId(plan);
                    double score = OffloadSupport.toStorableScore(plan.getScore());
                    boolean isSelected = (plan == selectedPlan);
                    
                    // Persist the regular plan
                    store.putPlan(personId, planId, plan, score, iter, isSelected);
                    
                    // Create a proxy for this plan
                    PlanProxy proxy = new PlanProxy(planId, person, store, 
                        plan.getType(), iter, score, isSelected);
                    
                    // Replace in the list (maintain same index)
                    allPlans.set(i, proxy);
                    
                    // Update selected plan reference if needed
                    if (isSelected) {
                        selectedPlan = proxy;
                    }
                    
                    regularPlansConverted++;
                }
            }
            
            // Rebuild person's plan list if we converted any plans
            if (regularPlansConverted > 0) {
                person.getPlans().clear();
                for (Plan plan : allPlans) {
                    person.addPlan(plan);
                }
                // Restore selected plan
                if (selectedPlan != null) {
                    person.setSelectedPlan(selectedPlan);
                }
            }
            
            // Second pass: dematerialize non-selected proxy plans
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
        
        if (regularPlansConverted > 0) {
            log.info("After replanning: Converted {} regular plans to proxies", regularPlansConverted);
        }
        if (dematerialized > 0) {
            log.info("After replanning: Dematerialized {} non-selected plans", dematerialized);
        }
    }
}
