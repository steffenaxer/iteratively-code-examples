package io.iteratively.matsim.offload;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import java.util.List;

public final class OffloadSupport {
    private OffloadSupport() {}

    /**
     * Loads all plans for a person as lightweight proxies.
     * Only headers (score, type, metadata) are loaded into memory.
     * Plan content is materialized on demand.
     */
    public static void loadAllPlansAsProxies(Person p, PlanStore store, int currentIteration) {
        String personId = p.getId().toString();
        List<PlanHeader> headers = store.listPlanHeaders(personId);
        
        if (headers.isEmpty()) {
            return;
        }
        
        // Clear existing plans
        p.getPlans().clear();
        
        // Load all plans as proxies
        String activePlanId = store.getActivePlanId(personId).orElse(null);
        for (PlanHeader header : headers) {
            PlanProxy proxy = new PlanProxy(header, p, store, currentIteration);
            p.addPlan(proxy);
            
            // Set the selected plan
            if (header.planId.equals(activePlanId)) {
                p.setSelectedPlan(proxy);
            }
        }
        
        // If no active plan was set, select the first one
        if (p.getSelectedPlan() == null && !p.getPlans().isEmpty()) {
            p.setSelectedPlan(p.getPlans().get(0));
        }
    }

    /**
     * Persists all materialized plans and dematerializes them to save memory.
     * Only writes plans that have been materialized (modified) back to the store.
     */
    public static void persistAllMaterialized(Person p, PlanStore store, int iter) {
        String personId = p.getId().toString();
        Plan selectedPlan = p.getSelectedPlan();
        String selectedPlanId = null;
        
        for (Plan plan : p.getPlans()) {
            if (plan instanceof PlanProxy proxy) {
                // Only persist if the plan was materialized (potentially modified)
                if (proxy.isMaterialized()) {
                    Plan materializedPlan = proxy.getMaterializedPlan();
                    String planId = proxy.getPlanId();
                    double score = plan.getScore() != null ? plan.getScore() : Double.NEGATIVE_INFINITY;
                    boolean isSelected = (plan == selectedPlan);
                    
                    store.putPlan(personId, planId, materializedPlan, score, iter, isSelected);
                    
                    if (isSelected) {
                        selectedPlanId = planId;
                    }
                } else {
                    // Update score even if not materialized
                    String planId = proxy.getPlanId();
                    double score = plan.getScore() != null ? plan.getScore() : Double.NEGATIVE_INFINITY;
                    store.updateScore(personId, planId, score, iter);
                    
                    if (plan == selectedPlan) {
                        selectedPlanId = planId;
                    }
                }
            } else {
                // Non-proxy plan - persist normally
                String planId = ensurePlanId(plan);
                double score = plan.getScore() != null ? plan.getScore() : Double.NEGATIVE_INFINITY;
                boolean isSelected = (plan == selectedPlan);
                
                store.putPlan(personId, planId, plan, score, iter, isSelected);
                
                if (isSelected) {
                    selectedPlanId = planId;
                }
            }
        }
        
        // Ensure the selected plan is properly marked in the store
        if (selectedPlanId != null) {
            store.setActivePlanId(personId, selectedPlanId);
        }
    }

    /**
     * Adds a new plan to a person and persists it immediately.
     * Used when replanning strategies create new plans.
     */
    public static void addNewPlan(Person p, Plan newPlan, PlanStore store, int iter) {
        String personId = p.getId().toString();
        String planId = ensurePlanId(newPlan);
        double score = newPlan.getScore() != null ? newPlan.getScore() : Double.NEGATIVE_INFINITY;
        
        // Persist the new plan to the store
        store.putPlan(personId, planId, newPlan, score, iter, false);
        
        // Create a proxy for the new plan and add it to the person
        List<PlanHeader> headers = store.listPlanHeaders(personId);
        PlanHeader newHeader = headers.stream()
            .filter(h -> h.planId.equals(planId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Plan not found after persist: " + planId));
        
        PlanProxy proxy = new PlanProxy(newHeader, p, store, iter);
        p.addPlan(proxy);
    }

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
