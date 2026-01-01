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
        if (sel == null) return;

        String personId = p.getId().toString();
        String planId = ensurePlanId(sel);
        double score = sel.getScore() == null ? Double.NEGATIVE_INFINITY : sel.getScore();

        // Nur persistieren wenn sich etwas geändert hat
        if (shouldPersist(sel)) {
            store.putPlan(personId, planId, sel, score, iter, true);
            markPersisted(sel);
        }
    }

    private static boolean shouldPersist(Plan plan) {
        Object lastHash = plan.getAttributes().getAttribute("offloadLastHash");
        int currentHash = computePlanHash(plan);
        return lastHash == null || (int) lastHash != currentHash;
    }

    private static void markPersisted(Plan plan) {
        plan.getAttributes().putAttribute("offloadLastHash", computePlanHash(plan));
    }

    private static int computePlanHash(Plan plan) {
        int hash = plan.getPlanElements().size();
        hash = 31 * hash + (plan.getScore() != null ? plan.getScore().hashCode() : 0);
        for (var element : plan.getPlanElements()) {
            hash = 31 * hash + element.hashCode();
        }
        return hash;
    }

    private static String ensurePlanId(Plan plan) {
        Object attr = plan.getAttributes().getAttribute("offloadPlanId");
        if (attr instanceof String s) return s;
        String pid = "p" + Math.abs(plan.getPlanElements().hashCode());
        plan.getAttributes().putAttribute("offloadPlanId", pid);
        return pid;
    }
}
