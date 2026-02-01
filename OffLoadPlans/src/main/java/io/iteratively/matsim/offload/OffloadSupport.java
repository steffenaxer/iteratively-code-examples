package io.iteratively.matsim.offload;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        List<PlanProxy> proxies = store.listPlanProxies(p);

        if (proxies.isEmpty()) {
            return;
        }

        p.getPlans().clear();

        Plan selectedPlan = null;
        
        for (PlanProxy proxy : proxies) {
            p.addPlan(proxy);
            if (proxy.isSelected()) {
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

    public static void ensureSelectedMaterialized(Person p) {
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
        Double score = store.listPlanProxies(p).stream()
                .filter(proxy -> proxy.getPlanId().equals(newPlanId))
                .map(PlanProxy::getScore)
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
     * Ensures a plan has a unique planId attribute.
     * If the plan doesn't have one, generates and assigns a new unique ID.
     * 
     * @param plan the plan to ensure has an ID
     * @return the planId of the plan
     */
    public static String ensurePlanId(Plan plan) {
        Object attr = plan.getAttributes().getAttribute("offloadPlanId");
        if (attr instanceof String s) return s;
        String pid = "p" + System.nanoTime() + "_" + Math.abs(plan.hashCode());
        plan.getAttributes().putAttribute("offloadPlanId", pid);
        return pid;
    }

    /**
     * Extracts the planId from a Plan object.
     * Handles both PlanProxy and regular Plan instances.
     * 
     * @param plan the Plan to extract planId from
     * @return planId if available, null if the plan doesn't have a planId assigned
     *         (typically happens for regular Plans that haven't been persisted yet)
     */
    public static String getPlanId(Plan plan) {
        if (plan instanceof PlanProxy proxy) {
            return proxy.getPlanId();
        } else {
            Object attr = plan.getAttributes().getAttribute("offloadPlanId");
            if (attr instanceof String s) {
                return s;
            }
            return null;
        }
    }

    /**
     * Collects all planIds from a Person's plan list.
     * Only includes plans that have a planId assigned (i.e., plans that have been
     * persisted to the store or are PlanProxy instances).
     * 
     * @param person the Person whose plan IDs should be collected
     * @return Set of planIds for all plans in the Person that have IDs.
     *         Plans without planIds are filtered out (not included in the result).
     */
    public static Set<String> collectActivePlanIds(Person person) {
        Set<String> activePlanIds = new HashSet<>();
        for (Plan plan : person.getPlans()) {
            String planId = getPlanId(plan);
            if (planId != null) {
                activePlanIds.add(planId);
            }
        }
        return activePlanIds;
    }
}
