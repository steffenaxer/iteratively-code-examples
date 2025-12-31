
package io.iteratively.matsim.offload;

import java.util.List;
import java.util.Optional;
import org.matsim.api.core.v01.population.Plan;

public interface PlanStore extends AutoCloseable {
    Optional<String> getActivePlanId(String personId);
    void setActivePlanId(String personId, String planId);
    List<PlanHeader> listPlanHeaders(String personId);
    void putPlan(String personId, String planId, Plan plan, double score, int iter, boolean makeSelected);
    void updateScore(String personId, String planId, double score, int iter);
    Plan materialize(String personId, String planId);
    boolean hasPlan(String personId, String planId);
    List<String> listPlanIds(String personId);
    void deletePlan(String personId, String planId);
    void commit();
    @Override void close();
}
