package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.StreamingPopulationWriter;
import org.matsim.core.utils.io.IOUtils;

import java.io.OutputStream;

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
        dumpPlanStore(event);
    }

    private void dumpPlanStore(ShutdownEvent event) {
        planStore.commit();
        
        var config = event.getServices().getConfig();
        String outputDir = config.controller().getOutputDirectory();
        var compressionType = config.controller().getCompressionType();

        String fullPath = outputDir + "/output_plans_withStore.xml" + compressionType.fileEnding;

        Population population = scenario.getPopulation();

        // StreamingPopulationWriter writes persons one by one without keeping all in memory
        StreamingPopulationWriter streamingWriter = new StreamingPopulationWriter();
        streamingWriter.startStreaming(fullPath);

        for (Person person : population.getPersons().values()) {
            String personId = person.getId().toString();

            // Create temporary person to avoid modifying the original population
            Person tempPerson = PopulationUtils.getFactory().createPerson(person.getId());

            // Copy attributes
            for (var entry : person.getAttributes().getAsMap().entrySet()) {
                tempPerson.getAttributes().putAttribute(entry.getKey(), entry.getValue());
            }
            // Load plans from store and add to temp person
            String activePlanId = planStore.getActivePlanId(personId).orElse(null);

            for (String planId : planStore.listPlanIds(personId)) {
                Plan plan = planStore.materialize(personId, planId);
                tempPerson.addPlan(plan);

                if (planId.equals(activePlanId)) {
                    tempPerson.setSelectedPlan(plan);
                }
            }

            // Write person to stream - memory is freed after each person
            streamingWriter.writePerson(tempPerson);
        }

        streamingWriter.closeStreaming();
        planStore.close();
    }
}
