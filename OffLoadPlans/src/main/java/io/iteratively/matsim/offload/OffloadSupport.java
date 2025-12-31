package io.iteratively.matsim.offload;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import java.util.List;

public final class OffloadSupport {
    private OffloadSupport() {}

    public static void ensureSelectedMaterialized(Person p, PlanStore store, PlanCache cache) {
        String personId = p.getId().toString();
        String active = store.getActivePlanId(personId).orElse(null);
        if (active == null) return;
        List<? extends Plan> plans = p.getPlans();
        if (plans.size() == 1 && plans.get(0) == p.getSelectedPlan()) return;
        p.getPlans().clear();
        Plan selected = cache.materialize(personId, active);
        Double score = store.listPlanHeaders(personId).stream()
                .filter(h -> h.planId.equals(active))
                .map(h -> h.score)
                .findFirst().orElse(null);
        selected.setScore(score);
        p.addPlan(selected);
        p.setSelectedPlan(selected);
    }

    public static void swapSelectedPlanTo(Person p, PlanStore store, String newPlanId) {
        String personId = p.getId().toString();
        Plan newPlan = store.materialize(personId, newPlanId);
        p.getPlans().clear();
        Double score = store.listPlanHeaders(personId).stream()
                .filter(h -> h.planId.equals(newPlanId))
                .map(h -> h.score)
                .findFirst().orElse(null);
        newPlan.setScore(score);
        p.addPlan(newPlan);
        p.setSelectedPlan(newPlan);
    }

    public static void persistSelectedIfAny(Person p, PlanStore store, int iter) {
        Plan sel = p.getSelectedPlan();
        String personId = p.getId().toString();
        String planId = ensurePlanId(sel);
        double score = sel.getScore() == null ? Double.NEGATIVE_INFINITY : sel.getScore();
        store.putPlan(personId, planId, sel, score, iter, true);
    }

    private static String ensurePlanId(Plan plan) {
        Object attr = plan.getAttributes().getAttribute("offloadPlanId");
        if (attr instanceof String s) return s;
        String pid = "p" + Math.abs(plan.getPlanElements().hashCode());
        plan.getAttributes().putAttribute("offloadPlanId", pid);
        return pid;
    }
}
