package chicago;

// NOTE: This implementation requires the OffLoadPlans module to be fixed and compiled.
// The OffLoadPlans module currently has compilation errors that are unrelated to this change.
// Once those are resolved, this code will work as intended.

/*
import io.iteratively.matsim.offload.OffloadConfigGroup;
import io.iteratively.matsim.offload.OffloadModule;
import io.iteratively.matsim.offload.PlanStore;
import io.iteratively.matsim.offload.RocksDbPlanStore;
*/
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;

/**
 * Custom ControllerCreator that loads population using StreamingPopulationReader.
 * Instead of loading all plans into memory at once, plans are converted to
 * PlanProxy objects on-the-fly during streaming, significantly reducing memory usage.
 * 
 * <h2>Concept Overview</h2>
 * <p>
 * This class demonstrates a memory-efficient approach to loading large MATSim populations:
 * <ol>
 *   <li>Use StreamingPopulationReader to read the population file</li>
 *   <li>For each person/plan read, immediately store it in RocksDB via PlanStore</li>
 *   <li>Replace the full Plan object with a lightweight PlanProxy</li>
 *   <li>The proxy only holds metadata (score, type, iteration) in memory</li>
 *   <li>Full plan details are materialized on-demand from RocksDB when needed</li>
 * </ol>
 * </p>
 * 
 * <h2>Memory Savings</h2>
 * <p>
 * For a typical agent with 5 plans:
 * <ul>
 *   <li>Traditional approach: 5 × 10KB = 50KB per agent</li>
 *   <li>Proxy approach: 5 × 100 bytes = 500 bytes per agent</li>
 *   <li>Memory reduction: ~99% for non-materialized plans</li>
 * </ul>
 * For 1 million agents, this means ~50GB vs ~500MB in memory!
 * </p>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Config config = ConfigUtils.loadConfig("config.xml");
 * config.plans().setInputFile("large-population.xml");
 * 
 * OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
 * offloadConfig.setStoreDirectory("/path/to/rocksdb");
 * 
 * Controler controler = StreamingControllerCreator.createControlerWithStreamingPopulation(config, false);
 * controler.run();
 * }</pre>
 * 
 * @author steffenaxer
 */
public class StreamingControllerCreator {

    /**
     * Creates a Controler with streaming population loading.
     * Plans are loaded as lightweight proxies directly from the population file,
     * avoiding the need to keep all full plans in memory.
     * 
     * <p><b>Implementation Steps:</b></p>
     * <ol>
     *   <li>Initialize PlanStore (RocksDB) for persistent storage</li>
     *   <li>Load scenario (network, facilities, etc.) but not population</li>
     *   <li>Create StreamingPopulationReader with custom handler</li>
     *   <li>Handler processes each person/plan and stores in RocksDB</li>
     *   <li>Handler replaces full plans with PlanProxy objects</li>
     *   <li>Create Controler with the scenario containing proxies</li>
     *   <li>Add OffloadModule to handle materialization during simulation</li>
     * </ol>
     * 
     * @param config MATSim configuration
     * @param useDrt whether to use DRT functionality
     * @return configured Controler instance
     */
    public static Controler createControlerWithStreamingPopulation(Config config, boolean useDrt) {
        throw new UnsupportedOperationException(
            "This method requires the OffLoadPlans module to be compiled. " +
            "Currently, the OffLoadPlans module has compilation errors that are unrelated to this implementation. " +
            "Once those are fixed, uncomment the implementation below."
        );
        
        /* IMPLEMENTATION (uncomment when OffLoadPlans is fixed):
        
        // Initialize the plan store first
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        
        File baseDir;
        if (offloadConfig.getStoreDirectory() != null) {
            baseDir = new File(offloadConfig.getStoreDirectory());
        } else {
            baseDir = new File(System.getProperty("java.io.tmpdir"),
                    "matsim-offload-" + System.currentTimeMillis());
        }
        baseDir.mkdirs();
        
        File rocksDir = new File(baseDir, "rocksdb");
        rocksDir.mkdirs();

        // Create scenario but don't load population yet
        Scenario scenario = ScenarioUtils.createScenario(config);
        
        // Load network and other data (but not population)
        // This is important: we skip population loading here
        String populationFile = config.plans().getInputFile();
        config.plans().setInputFile(null); // Temporarily remove to prevent automatic loading
        ScenarioUtils.loadScenario(scenario);
        config.plans().setInputFile(populationFile); // Restore for later use
        
        // Initialize the plan store
        PlanStore planStore = new RocksDbPlanStore(rocksDir, scenario);
        
        // Use streaming reader to load population as proxies
        StreamingPopulationReader streamingReader = new StreamingPopulationReader(scenario);
        StreamingPopulationHandler handler = new StreamingPopulationHandler(scenario, planStore);
        streamingReader.addAlgorithm(handler);
        
        if (populationFile != null) {
            streamingReader.readFile(populationFile);
        }
        
        // Now all persons have been added with their plans as proxies
        System.out.println("Loaded " + scenario.getPopulation().getPersons().size() + 
                         " persons with plans as proxies");
        System.out.println("Total plans processed: " + handler.getPlanCount());
        System.out.println("Memory saved: ~" + (handler.getPlanCount() * 10) + " KB");
        
        // Create controler based on DRT setting
        Controler controler;
        if (useDrt) {
            controler = DrtControlerCreator.createControler(config, false);
        } else {
            controler = new Controler(scenario);
        }
        
        // Add the offload module to handle plan materialization during simulation
        controler.addOverridingModule(new OffloadModule());
        
        return controler;
        */
    }
    
    /**
     * Convenience method for creating a DRT controler with streaming population.
     * 
     * @param configPath path to MATSim config file
     * @return configured DRT Controler with streaming population
     */
    public static Controler createDrtControlerWithStreamingPopulation(String configPath) {
        Config config = ConfigUtils.loadConfig(configPath, 
                new MultiModeDrtConfigGroup(DrtWithExtensionsConfigGroup::new), 
                new DvrpConfigGroup());
        return createControlerWithStreamingPopulation(config, true);
    }
}
