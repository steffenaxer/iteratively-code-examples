package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.controler.events.ReplanningEvent;
import org.matsim.core.controler.listener.ReplanningListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Listener that runs after replanning to immediately convert any regular Plan objects
 * (created by strategies like createCopyOfSelectedPlanAndMakeSelected) to PlanProxy objects.
 * 
 * This ensures that plans created during replanning don't stay in memory as full Plan objects,
 * maintaining the memory efficiency of the offload module throughout the entire iteration.
 */
public final class ReplanningConversionListener implements ReplanningListener {
    private static final Logger log = LogManager.getLogger(ReplanningConversionListener.class);

    private final PlanStore store;
    private final PlanCache cache;

    @Inject
    public ReplanningConversionListener(PlanStore store, PlanCache cache) {
        this.store = store;
        this.cache = cache;
    }

    @Override
    public void notifyReplanning(ReplanningEvent event) {
        int iter = event.getIteration();
        var population = event.getServices().getScenario().getPopulation();
        
        int conversions = 0;
        
        for (Person person : population.getPersons().values()) {
            conversions += convertRegularPlansToProxies(person, iter);
        }
        
        if (conversions > 0) {
            store.commit();
            log.debug("Iteration {}: Converted {} regular plans to proxies after replanning", 
                iter, conversions);
        }
    }
    
    /**
     * Converts any regular Plan objects in the person's plans list to PlanProxy objects.
     * Returns the number of plans converted.
     */
    private int convertRegularPlansToProxies(Person person, int iter) {
        String personId = person.getId().toString();
        List<? extends Plan> plans = person.getPlans();
        
        // Track regular plans that need to be converted
        List<Integer> regularPlanIndices = new ArrayList<>();
        List<PlanProxy> replacementProxies = new ArrayList<>();
        
        for (int i = 0; i < plans.size(); i++) {
            Plan plan = plans.get(i);
            
            // Only convert regular plans, skip proxies
            if (!(plan instanceof PlanProxy)) {
                // Persist the plan first
                String planId = OffloadSupport.ensurePlanId(plan);
                double score = OffloadSupport.toStorableScore(plan.getScore());
                boolean isSelected = (plan == person.getSelectedPlan());
                
                store.putPlan(personId, planId, plan, score, iter, isSelected);
                
                // Create proxy to replace the regular plan
                PlanProxy proxy = new PlanProxy(planId, person, store, plan.getType(), 
                    plan.getPlanMutator(), iter, plan.getScore());
                    
                regularPlanIndices.add(i);
                replacementProxies.add(proxy);
            }
        }
        
        // Replace regular plans with proxies
        for (int i = 0; i < regularPlanIndices.size(); i++) {
            int index = regularPlanIndices.get(i);
            PlanProxy proxy = replacementProxies.get(i);
            Plan oldPlan = plans.get(index);
            
            // Remove the old plan and insert the proxy at the same position
            person.getPlans().remove(index);
            person.getPlans().add(index, proxy);
            
            // If the old plan was selected, update the selected plan to the proxy
            // and ensure it's materialized for use
            if (oldPlan == person.getSelectedPlan()) {
                person.setSelectedPlan(proxy);
                // Materialize it immediately if it will be used
                OffloadSupport.ensureSelectedMaterialized(person, store, cache);
            }
        }
        
        return regularPlanIndices.size();
    }
}
