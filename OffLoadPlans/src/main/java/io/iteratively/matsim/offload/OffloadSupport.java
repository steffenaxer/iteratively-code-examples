package io.iteratively.matsim.offload;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;

import java.util.List;

public final class OffloadSupport {
    private OffloadSupport() {}

    public record PersistTask(String personId, String planId, byte[] blob, double score) {}

    public static boolean isValidScore(Double score) {
        return score != null && !score.isNaN() && !score.isInfinite();
    }

    public static double toStorableScore(Double score) {
        return isValidScore(score) ? score : Double.NEGATIVE_INFINITY;
    }

    public static void loadAllPlansAsProxies(Person p, PlanStore store) {
        String personId = p.getId().toString();
        List<PlanHeader> headers = store.listPlanHeaders(personId);

        if (headers.isEmpty()) {
            return;
        }

        p.getPlans().clear();

        Plan selectedPlan = null;
        for (PlanHeader h : headers) {
            PlanProxy proxy = new PlanProxy(h, p, store);
            p.addPlan(proxy);
            if (h.selected) {
                selectedPlan = proxy;
            }
        }

        if (selectedPlan != null) {
            p.setSelectedPlan(selectedPlan);
        } else if (!p.getPlans().isEmpty()) {
            p.setSelectedPlan(p.getPlans().get(0));
        }
    }

    public static void persistAllMaterialized(Person p, PlanStore store, int iter) {
        String personId = p.getId().toString();

        for (Plan plan : p.getPlans()) {
            if (plan instanceof PlanProxy proxy) {
                if (proxy.isMaterialized()) {
                    Plan materialized = proxy.getMaterializedPlan();
                    if (shouldPersist(materialized)) {
                        String planId = proxy.getPlanId();
                        double score = toStorableScore(proxy.getScore());
                        boolean isSelected = (plan == p.getSelectedPlan());
                        store.putPlan(personId, planId, materialized, score, iter, isSelected);
                        markPersisted(materialized);
                    }
                    proxy.dematerialize();
                }
            } else {
                if (shouldPersist(plan)) {
                    String planId = ensurePlanId(plan);
                    double score = toStorableScore(plan.getScore());
                    boolean isSelected = (plan == p.getSelectedPlan());
                    store.putPlan(personId, planId, plan, score, iter, isSelected);
                    markPersisted(plan);
                }
            }
        }
    }

    public static void addNewPlan(Person p, Plan plan, PlanStore store, int iter) {
        String personId = p.getId().toString();
        String planId = ensurePlanId(plan);
        double score = toStorableScore(plan.getScore());

        store.putPlan(personId, planId, plan, score, iter, false);
        markPersisted(plan);

        PlanProxy proxy = new PlanProxy(planId, p, store, plan.getType(), iter, plan.getScore());
        p.addPlan(proxy);
    }

    public static void ensureSelectedMaterialized(Person p, PlanStore store, PlanCache cache) {
        Plan selected = p.getSelectedPlan();
        if (selected == null) return;

        if (selected instanceof PlanProxy proxy) {
            proxy.getMaterializedPlan();
        }
    }

    public static void swapSelectedPlanTo(Person p, PlanStore store, String newPlanId) {
        String personId = p.getId().toString();

        for (Plan plan : p.getPlans()) {
            if (plan instanceof PlanProxy proxy) {
                if (proxy.getPlanId().equals(newPlanId)) {
                    p.setSelectedPlan(proxy);
                    store.setActivePlanId(personId, newPlanId);
                    return;
                }
            }
        }

        Plan newPlan = store.materialize(personId, newPlanId);
        Double score = store.listPlanHeaders(personId).stream()
                .filter(h -> h.planId.equals(newPlanId))
                .map(h -> h.score)
                .findFirst().orElse(null);
        newPlan.setScore(score);
        p.getPlans().clear();
        p.addPlan(newPlan);
        p.setSelectedPlan(newPlan);
        store.setActivePlanId(personId, newPlanId);
    }

    public static PersistTask preparePersist(Person p, FuryPlanCodec codec) {
        Plan sel = p.getSelectedPlan();
        if (sel == null || !shouldPersist(sel)) return null;

        String personId = p.getId().toString();
        String planId = ensurePlanId(sel);
        double score = toStorableScore(sel.getScore());
        byte[] blob = codec.serialize(sel);

        markPersisted(sel);
        return new PersistTask(personId, planId, blob, score);
    }

    public static void persistSelectedIfAny(Person p, PlanStore store, int iter) {
        Plan sel = p.getSelectedPlan();
        if (sel == null) return;

        String personId = p.getId().toString();
        String planId = ensurePlanId(sel);
        double score = toStorableScore(sel.getScore());

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
        Double score = plan.getScore();
        if (isValidScore(score)) {
            hash = 31 * hash + score.hashCode();
        }
        for (var element : plan.getPlanElements()) {
            hash = 31 * hash + element.hashCode();
        }
        return hash;
    }

    /**
     * Ensures that a plan has a unique ID. If the plan already has an ID attribute,
     * it returns that ID. Otherwise, it generates a new unique ID and stores it.
     * 
     * @param plan the plan to ensure has an ID
     * @return the plan's ID
     */
    public static String ensurePlanId(Plan plan) {
        Object attr = plan.getAttributes().getAttribute("offloadPlanId");
        if (attr instanceof String s) return s;
        String pid = "p" + System.nanoTime() + "_" + Math.abs(plan.hashCode());
        plan.getAttributes().putAttribute("offloadPlanId", pid);
        return pid;
    }
}
