package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.StreamingPopulationWriter;

public class PlanStoreShutdownListener implements ShutdownListener {

    private final PlanStore planStore;
    private final Scenario scenario;

    @Inject
    public PlanStoreShutdownListener(PlanStore planStore, Scenario scenario) {
        this.planStore = planStore;
        this.scenario = scenario;
    }

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        planStore.commit();
        dumpPlanStore(event);
        planStore.close();
    }

    private void dumpPlanStore(ShutdownEvent event) {
        var config = event.getServices().getConfig();
        var controller = config.controller();
        String fullPath = controller.getOutputDirectory() + "/output_plans_fromStore.xml" 
                + controller.getCompressionType().fileEnding;

        StreamingPopulationWriter writer = new StreamingPopulationWriter();
        writer.startStreaming(fullPath);

        for (Person person : scenario.getPopulation().getPersons().values()) {
            String personId = person.getId().toString();
            Person tempPerson = PopulationUtils.getFactory().createPerson(person.getId());

            // Copy attributes
            person.getAttributes().getAsMap().forEach((key, value) -> 
                    tempPerson.getAttributes().putAttribute(key, value));

            // Load plans from store and add to temp person
            String activePlanId = planStore.getActivePlanId(personId).orElse(null);
            for (String planId : planStore.listPlanIds(personId)) {
                Plan plan = planStore.materialize(personId, planId);
                tempPerson.addPlan(plan);
                if (planId.equals(activePlanId)) {
                    tempPerson.setSelectedPlan(plan);
                }
            }

            writer.writePerson(tempPerson);
        }

        writer.closeStreaming();
    }

}
