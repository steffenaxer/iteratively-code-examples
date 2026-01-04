package io.iteratively.matsim.offload;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.utils.objectattributes.attributable.Attributes;

import java.util.List;
import java.util.Map;

/**
 * Lightweight plan proxy that holds only header data.
 * Avoids loading full plans into memory during selection.
 * Score and type are cached separately to allow comparisons without materialization.
 */
public final class PlanProxy implements Plan {

    private final String planId;
    private final Person person;
    private final PlanStore store;
    private final int creationIter;

    // Cached values - work without materialization
    private Double score;
    private String type;
    private String planMutator;

    private Plan materializedPlan;
    private long materializationTimestamp = -1; // Timestamp when plan was materialized

    public PlanProxy(PlanHeader header, Person person, PlanStore store) {
        this.planId = header.planId;
        this.person = person;
        this.store = store;
        this.type = header.type;
        this.planMutator = header.planMutator;
        this.creationIter = header.creationIter;
        // NaN als null behandeln
        this.score = isValidScore(header.score) ? header.score : null;
    }

    public PlanProxy(String planId, Person person, PlanStore store, String type,
                     int creationIter, Double score) {
        this(planId, person, store, type, null, creationIter, score);
    }

    public PlanProxy(String planId, Person person, PlanStore store, String type,
                     String planMutator, int creationIter, Double score) {
        this.planId = planId;
        this.person = person;
        this.store = store;
        this.type = type;
        this.planMutator = planMutator;
        this.creationIter = creationIter;
        // NaN als null behandeln
        this.score = isValidScore(score) ? score : null;
    }

    private static boolean isValidScore(Double score) {
        return score != null && !score.isNaN() && !score.isInfinite();
    }

    public String getPlanId() {
        return planId;
    }

    @Override
    public Id<Plan> getId() {
        return Id.create(planId, Plan.class);
    }

    @Override
    public void setPlanId(Id<Plan> planId) {
        throw new UnsupportedOperationException("Cannot change planId on PlanProxy");
    }

    // --- Score: works without materialization ---

    @Override
    public Double getScore() {
        if (materializedPlan != null) {
            Double matScore = materializedPlan.getScore();
            // NaN normalisieren
            if (matScore != null && (matScore.isNaN() || matScore.isInfinite())) {
                return null;
            }
            return matScore;
        }
        return score;
    }

    @Override
    public void setScore(Double score) {
        // NaN und Infinite als null behandeln
        if (score != null && (score.isNaN() || score.isInfinite())) {
            this.score = null;
        } else {
            this.score = score;
        }

        if (materializedPlan != null) {
            materializedPlan.setScore(this.score);
        }

        // Nur gültige Scores zum Store schreiben
        if (this.score != null) {
            store.updateScore(person.getId().toString(), planId, this.score, creationIter);
        }
    }

    // --- Type: works without materialization ---

    @Override
    public String getType() {
        if (materializedPlan != null) {
            return materializedPlan.getType();
        }
        return type;
    }

    @Override
    public void setType(String type) {
        this.type = type;
        if (materializedPlan != null) {
            materializedPlan.setType(type);
        }
    }

    // --- Person: works without materialization ---

    @Override
    public Person getPerson() {
        return person;
    }

    @Override
    public void setPerson(Person person) {
        if (materializedPlan != null) {
            materializedPlan.setPerson(person);
        }
    }

    // --- Iteration: works without materialization ---

    @Override
    public int getIterationCreated() {
        return creationIter;
    }

    @Override
    public void setIterationCreated(int iteration) {
        throw new UnsupportedOperationException("Cannot change iterationCreated on PlanProxy");
    }

    // --- PlanMutator: works without materialization ---

    @Override
    public String getPlanMutator() {
        if (materializedPlan != null) {
            return materializedPlan.getPlanMutator();
        }
        return planMutator;
    }

    @Override
    public void setPlanMutator(String mutator) {
        this.planMutator = mutator;
        if (materializedPlan != null) {
            materializedPlan.setPlanMutator(mutator);
        }
    }

    // --- Methods requiring materialization ---

    @Override
    public List<PlanElement> getPlanElements() {
        materializeIfNeeded();
        return materializedPlan.getPlanElements();
    }

    @Override
    public void addLeg(Leg leg) {
        materializeIfNeeded();
        materializedPlan.addLeg(leg);
    }

    @Override
    public void addActivity(Activity activity) {
        materializeIfNeeded();
        materializedPlan.addActivity(activity);
    }

    @Override
    public Attributes getAttributes() {
        materializeIfNeeded();
        return materializedPlan.getAttributes();
    }

    @Override
    public Map<String, Object> getCustomAttributes() {
        materializeIfNeeded();
        return materializedPlan.getCustomAttributes();
    }

    // --- Materialization control ---

    private void materializeIfNeeded() {
        if (materializedPlan == null) {
            materializedPlan = store.materialize(person.getId().toString(), planId);
            if (materializedPlan == null) {
                throw new IllegalStateException(
                        "Failed to materialize plan: personId=" + person.getId() +
                                ", planId=" + planId + ". Plan not found in store.");
            }
            if (score != null) {
                materializedPlan.setScore(score);
            }
            if (type != null) {
                materializedPlan.setType(type);
            }
            if (planMutator != null) {
                materializedPlan.setPlanMutator(planMutator);
            }
            materializationTimestamp = System.currentTimeMillis();
        }
    }

    public Plan getMaterializedPlan() {
        materializeIfNeeded();
        return materializedPlan;
    }

    public boolean isMaterialized() {
        return materializedPlan != null;
    }

    public void dematerialize() {
        if (materializedPlan != null) {
            Double matScore = materializedPlan.getScore();
            // NaN normalisieren beim Dematerialisieren
            this.score = isValidScore(matScore) ? matScore : null;
            this.type = materializedPlan.getType();
            this.planMutator = materializedPlan.getPlanMutator();
            this.materializedPlan = null;
            this.materializationTimestamp = -1;
        }
    }

    /**
     * Returns the timestamp (in milliseconds) when this plan was materialized.
     * Returns -1 if the plan is not currently materialized.
     */
    public long getMaterializationTimestamp() {
        return materializationTimestamp;
    }

    /**
     * Returns the number of milliseconds this plan has been materialized.
     * Returns -1 if the plan is not currently materialized.
     */
    public long getMaterializationDurationMs() {
        if (materializationTimestamp < 0) {
            return -1;
        }
        return System.currentTimeMillis() - materializationTimestamp;
    }

    @Override
    public String toString() {
        return "PlanProxy[planId=" + planId + ", personId=" + person.getId() +
                ", score=" + score + ", type=" + type +
                ", materialized=" + isMaterialized() + "]";
    }
}