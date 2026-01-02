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
        planStore.commit();
        planStore.close();
    }

}
