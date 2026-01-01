package io.iteratively.matsim.offload;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.utils.objectattributes.attributable.Attributes;

import java.util.List;
import java.util.Map;

/**
 * Lightweight plan proxy that holds only header data.
 * Avoids loading full plans into memory during selection.
 */
public final class PlanProxy implements Plan {

    private final PlanHeader header;
    private final Person person;
    private final PlanStore store;
    private final int currentIteration;
    private Plan materializedPlan;

    public PlanProxy(PlanHeader header, Person person, PlanStore store, int currentIteration) {
        this.header = header;
        this.person = person;
        this.store = store;
        this.currentIteration = currentIteration;
    }

    public String getPlanId() {
        return header.planId;
    }

    @Override
    public Id<Plan> getId() {
        return Id.create(header.planId, Plan.class);
    }

    @Override
    public void setPlanId(Id<Plan> planId) {
        // Plan-ID ist immutable im Proxy
        throw new UnsupportedOperationException("Cannot change planId on PlanProxy");
    }

    @Override
    public Double getScore() {
        return header.score;
    }

    @Override
    public void setScore(Double score) {
        header.score = score != null ? score : Double.NaN;
        store.updateScore(person.getId().toString(), header.planId, header.score, currentIteration);
    }

    @Override
    public String getType() {
        return header.type;
    }

    @Override
    public void setType(String type) {
        materializeIfNeeded();
        materializedPlan.setType(type);
    }

    @Override
    public Person getPerson() {
        return person;
    }

    @Override
    public void setPerson(Person person) {
        materializeIfNeeded();
        materializedPlan.setPerson(person);
    }

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

    private void materializeIfNeeded() {
        if (materializedPlan == null) {
            materializedPlan = store.materialize(person.getId().toString(), header.planId);
        }
    }

    public Plan getMaterializedPlan() {
        materializeIfNeeded();
        return materializedPlan;
    }

    @Override
    public int getIterationCreated() {
        return header.creationIter;
    }

    @Override
    public void setIterationCreated(int iteration) {
        // creationIter ist immutable im Proxy
        throw new UnsupportedOperationException("Cannot change iterationCreated on PlanProxy");
    }

    @Override
    public String getPlanMutator() {
        materializeIfNeeded();
        return materializedPlan.getPlanMutator();
    }

    @Override
    public void setPlanMutator(String mutator) {
        materializeIfNeeded();
        materializedPlan.setPlanMutator(mutator);
    }

    @Override
    public Map<String, Object> getCustomAttributes() {
        materializeIfNeeded();
        return materializedPlan.getCustomAttributes();
    }


    public boolean isMaterialized() {
        return materializedPlan != null;
    }
}
