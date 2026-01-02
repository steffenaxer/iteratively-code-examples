package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;

/**
 * Controller listener that loads population using streaming before the simulation starts.
 * 
 * <p>This listener intercepts the controller startup to ensure that all plans
 * (except selected plans) are pre-loaded into the plan store before iteration 0.
 * This prevents all plans from being loaded into memory at once.</p>
 * 
 * <p>The listener should only be used when loading from a pre-existing population file,
 * not when creating populations programmatically.</p>
 */
public class StreamingPopulationStartupListener implements StartupListener {
    private static final Logger log = LogManager.getLogger(StreamingPopulationStartupListener.class);
    
    private final PlanStore planStore;
    private final Scenario scenario;
    private final String populationFile;
    
    /**
     * Creates a new streaming population startup listener.
     * 
     * @param planStore the plan store to pre-load plans into
     * @param scenario the scenario (should have an empty population initially)
     * @param populationFile the path to the population file to load
     */
    @Inject
    public StreamingPopulationStartupListener(PlanStore planStore, Scenario scenario, String populationFile) {
        this.planStore = planStore;
        this.scenario = scenario;
        this.populationFile = populationFile;
    }
    
    @Override
    public void notifyStartup(StartupEvent event) {
        if (populationFile == null || populationFile.isEmpty()) {
            log.warn("No population file specified for streaming load - skipping");
            return;
        }
        
        log.info("Pre-loading population from file using streaming: {}", populationFile);
        
        // Clear any existing population (should be empty anyway)
        scenario.getPopulation().getPersons().clear();
        
        // Use streaming loader to load population into store
        StreamingPopulationLoader loader = new StreamingPopulationLoader(
            planStore, 
            scenario, 
            event.getServices().getConfig().controller().getFirstIteration()
        );
        
        loader.loadFromFile(populationFile);
        
        log.info("Population pre-loaded into plan store. {} persons in scenario population.",
            scenario.getPopulation().getPersons().size());
    }
}
