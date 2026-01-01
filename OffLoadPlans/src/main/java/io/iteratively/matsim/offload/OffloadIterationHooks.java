
package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;



public final class OffloadIterationHooks implements IterationStartsListener, IterationEndsListener {
    private final PlanStore store;
    private final PlanCache cache;

    @Inject
    public OffloadIterationHooks(PlanStore store, PlanCache cache) {
        this.store = store;
        this.cache = cache;
    }

    @Override public void notifyIterationStarts(IterationStartsEvent event) {
        int iter = event.getIteration();
        var pop = event.getServices().getScenario().getPopulation();
        
        // Load all plans as proxies for each person
        // This allows ChangeExpBeta and other selectors to work on all plans
        for (Person p : pop.getPersons().values()) {
            OffloadSupport.loadAllPlansAsProxies(p, store, iter);
        }
        store.commit();
    }

    @Override public void notifyIterationEnds(IterationEndsEvent event) {
        int iter = event.getIteration();
        var pop = event.getServices().getScenario().getPopulation();
        
        // Persist all materialized plans (those that were modified)
        // and update scores for all plans
        for (Person p : pop.getPersons().values()) {
            OffloadSupport.persistAllMaterialized(p, store, iter);
            p.getPlans().clear();
        }
        store.commit();
        cache.evictAll();
    }
}
