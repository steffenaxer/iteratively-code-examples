package io.iteratively.matsim.offload;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.controler.AbstractModule;

/**
 * Module extension that adds streaming population loading capability.
 * 
 * <p><b>DEPRECATED:</b> The recommended approach is now to use {@link StreamingScenarioHelper#loadScenarioWithStreaming(Config)}
 * which loads the population with streaming BEFORE the Controler is created. This ensures
 * that at injection time, the scenario already contains persons with selected plans and
 * the plan store already contains all plans.</p>
 * 
 * <p>Recommended usage:</p>
 * <pre>{@code
 * Config config = ConfigUtils.loadConfig("config.xml");
 * config.plans().setInputFile("population.xml.gz");
 * 
 * // Configure offload
 * OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
 * offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
 * offloadConfig.setStoreDirectory("planstore");
 * 
 * // Load scenario with streaming - this loads all plans into store and selected plans into scenario
 * Scenario scenario = StreamingScenarioHelper.loadScenarioWithStreaming(config);
 * 
 * Controler controler = new Controler(scenario);
 * controler.addOverridingModule(new OffloadModule());
 * controler.run();
 * }</pre>
 * 
 * <p>This module can still be used for explicit population file specification:</p>
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
