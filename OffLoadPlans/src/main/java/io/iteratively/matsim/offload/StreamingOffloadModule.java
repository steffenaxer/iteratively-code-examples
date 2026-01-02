package io.iteratively.matsim.offload;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.controler.AbstractModule;

/**
 * Module extension that adds streaming population loading capability.
 * 
 * <p>This module should be used in addition to {@link OffloadModule} when you want
 * to load a population from a file using streaming, which prevents all plans from
 * being loaded into memory at once.</p>
 * 
 * <p>Usage example:</p>
 * <pre>{@code
 * Config config = ConfigUtils.loadConfig("config.xml");
 * Scenario scenario = ScenarioUtils.createScenario(config);
 * // Do NOT load population here - let the streaming loader do it
 * 
 * Controler controler = new Controler(scenario);
 * controler.addOverridingModule(new OffloadModule());
 * controler.addOverridingModule(new StreamingOffloadModule("path/to/population.xml.gz"));
 * controler.run();
 * }</pre>
 */
public class StreamingOffloadModule extends AbstractModule {
    
    private final String populationFile;
    
    /**
     * Creates a new streaming offload module.
     * 
     * @param populationFile the path to the population file to load using streaming
     */
    public StreamingOffloadModule(String populationFile) {
        this.populationFile = populationFile;
    }
    
    @Override
    public void install() {
        // Add the streaming population startup listener
        addControllerListenerBinding().to(StreamingPopulationStartupListener.class);
    }
    
    @Provides
    @Singleton
    String providePopulationFile() {
        return populationFile;
    }
}
