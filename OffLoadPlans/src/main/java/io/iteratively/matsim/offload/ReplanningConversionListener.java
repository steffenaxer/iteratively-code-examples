package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.controler.events.ReplanningEvent;
import org.matsim.core.controler.listener.ReplanningListener;

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
            // Persist any regular plans that need to be persisted
            String personId = person.getId().toString();
            for (Plan plan : person.getPlans()) {
                if (!(plan instanceof PlanProxy)) {
                    String planId = OffloadSupport.ensurePlanId(plan);
                    double score = OffloadSupport.toStorableScore(plan.getScore());
                    boolean isSelected = (plan == person.getSelectedPlan());
                    store.putPlan(personId, planId, plan, score, iter, isSelected);
                }
            }
            
            // Convert regular plans to proxies using shared method
            conversions += OffloadSupport.convertRegularPlansToProxies(person, store, iter);
            
            // Ensure selected plan is materialized if it was converted
            OffloadSupport.ensureSelectedMaterialized(person, store, cache);
        }
        
        if (conversions > 0) {
            store.commit();
            log.debug("Iteration {}: Converted {} regular plans to proxies after replanning", 
                iter, conversions);
        }
    }
}
