package io.iteratively.matsim.offload;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.replanning.selectors.PlanSelector;

import java.util.Comparator;

public final class HeaderBasedPlanSelector implements PlanSelector<Plan, Person> {
    private final PlanStore store;

    public HeaderBasedPlanSelector(PlanStore store) {
        this.store = store;
    }

    @Override
    public Plan selectPlan(org.matsim.api.core.v01.population.HasPlansAndId<Plan, Person> person) {
        String pid = person.getId().toString();
        var headers = store.listPlanHeaders(pid);
        if (headers.isEmpty()) {
            return person.getSelectedPlan();
        }
        var best = headers.stream().max(Comparator.comparingDouble(h -> h.score)).orElse(null);
        if (best == null) {
            return person.getSelectedPlan();
        }
        var current = store.getActivePlanId(pid).orElse(null);
        if (!best.planId.equals(current)) {
            store.setActivePlanId(pid, best.planId);
            OffloadSupport.swapSelectedPlanTo((Person) person, store, best.planId);
        }
        store.commit();
        return person.getSelectedPlan();
    }
}
