package io.iteratively.matsim.offload;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;

import java.util.List;
import java.util.Optional;

public interface PlanStore extends AutoCloseable {
    Optional<String> getActivePlanId(String personId);
    void setActivePlanId(String personId, String planId);
    List<PlanProxy> listPlanProxies(Person person);
    void putPlan(String personId, String planId, Plan plan, double score, int iter, boolean makeSelected);
    void putPlanRaw(String personId, String planId, byte[] blob, double score, int iter, boolean makeSelected);
    void updateScore(String personId, String planId, double score, int iter);
    Plan materialize(String personId, String planId);
    boolean hasPlan(String personId, String planId);
    List<String> listPlanIds(String personId);
    void deletePlan(String personId, String planId);
    void commit();
    FuryPlanCodec getCodec();

    @Override
    void close(); // Von AutoCloseable, ohne throws Exception
}
