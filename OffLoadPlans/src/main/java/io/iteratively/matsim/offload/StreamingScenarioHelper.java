package io.iteratively.matsim.offload;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;

/**
 * Helper class for creating scenarios that use streaming population loading.
 * 
 * <p>This helper ensures that the population is loaded using streaming BEFORE
 * the Controler is created, so that at injection time:</p>
 * <ul>
 *   <li>The scenario contains persons with selected plans as proxies</li>
 *   <li>The plan store contains all plans (including non-selected ones)</li>
 *   <li>No scenario content needs to be resolved during injection</li>
 * </ul>
 * 
 * <p>Usage example:</p>
 * <pre>{@code
 * Config config = ConfigUtils.loadConfig("config.xml");
 * config.plans().setInputFile("population.xml.gz");
 * 
 * // Configure offload
 * OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
 * offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
 * offloadConfig.setStoreDirectory("planstore");
 * 
 * // Create scenario with streaming - this loads selected plans into scenario
 * // and all plans into the plan store
 * Scenario scenario = StreamingScenarioHelper.loadScenarioWithStreaming(config);
 * 
 * // At this point: scenario has persons with selected plan proxies,
 * // plan store has all plans
 * 
 * Controler controler = new Controler(scenario);
 * controler.addOverridingModule(new OffloadModule());
 * controler.run();
 * }</pre>
 */
public class StreamingScenarioHelper {
    private static final Logger log = LogManager.getLogger(StreamingScenarioHelper.class);
    
    /**
     * Loads a scenario using streaming population loading.
     * 
     * <p>This method:
     * <ol>
     *   <li>Saves the population file path from the config</li>
     *   <li>Temporarily sets the population file to null</li>
     *   <li>Loads the scenario (without population)</li>
     *   <li>Creates a plan store</li>
     *   <li>Uses streaming to load all plans into the store</li>
     *   <li>Loads all plans as proxies into the scenario</li>
     *   <li>Ensures selected plans are materialized</li>
     * </ol>
     * 
     * <p>After this method returns, the scenario is ready for injection with:</p>
     * <ul>
     *   <li>Persons with all plans as proxies in memory</li>
     *   <li>Selected plans materialized</li>
     *   <li>All plans stored in the plan store</li>
     * </ul>
     * 
     * @param config the MATSim config with population file and offload configuration
     * @return a scenario with streaming-loaded population
     */
    public static Scenario loadScenarioWithStreaming(Config config) {
        // Get the population file path
        String populationFile = config.plans().getInputFile();
        
        if (populationFile == null || populationFile.isEmpty()) {
            throw new IllegalArgumentException(
                "No population file specified in config. " +
                "Please set config.plans().setInputFile(...) before calling this method.");
        }
        
        log.info("Loading scenario with streaming population loading from: {}", populationFile);
        
        // Temporarily set to null to prevent loading during scenario creation
        config.plans().setInputFile(null);
        
        // Load scenario without population
        Scenario scenario = ScenarioUtils.loadScenario(config);
        
        // Get offload configuration
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        int maxPlans = config.replanning().getMaxAgentPlanMemorySize();
        
        // Create plan store directory
        File baseDir;
        if (offloadConfig.getStoreDirectory() != null) {
            baseDir = new File(offloadConfig.getStoreDirectory());
        } else {
            baseDir = new File(System.getProperty("java.io.tmpdir"),
                    "matsim-offload-" + System.currentTimeMillis());
        }
        baseDir.mkdirs();
        
        // Create plan store
        PlanStore planStore = switch (offloadConfig.getStorageBackend()) {
            case MAPDB -> {
                File dbFile = new File(baseDir, OffloadConfigGroup.DB_FILE_NAME);
                yield new MapDbPlanStore(dbFile, scenario, maxPlans);
            }
            case ROCKSDB -> {
                File rocksDir = new File(baseDir, "rocksdb");
                rocksDir.mkdirs();
                yield new RocksDbPlanStore(rocksDir, scenario, maxPlans);
            }
        };
        
        // Create plan cache
        PlanCache planCache = new PlanCache(planStore, offloadConfig.getCacheEntries());
        
        try {
            // Use streaming loader to load population into store
            log.info("Streaming population from file...");
            StreamingPopulationLoader loader = new StreamingPopulationLoader(
                planStore, 
                scenario, 
                config.controller().getFirstIteration()
            );
            loader.loadFromFile(populationFile);
            
            log.info("Loading plans as proxies into scenario...");
            // Load all plans as proxies into the scenario
            scenario.getPopulation().getPersons().values().forEach(person -> {
                OffloadSupport.loadAllPlansAsProxies(person, planStore);
            });
            
            log.info("Materializing selected plans...");
            // Ensure selected plans are materialized
            scenario.getPopulation().getPersons().values().forEach(person -> {
                OffloadSupport.ensureSelectedMaterialized(person, planStore, planCache);
            });
            
            planStore.commit();
            
            log.info("Scenario loaded with streaming. {} persons with plans in memory and store.",
                scenario.getPopulation().getPersons().size());
            
            // Restore the population file path in config for other components that might need it
            config.plans().setInputFile(populationFile);
            
        } catch (Exception e) {
            // Close plan store if something goes wrong
            planStore.close();
            throw new RuntimeException("Failed to load scenario with streaming", e);
        }
        
        return scenario;
    }
}
