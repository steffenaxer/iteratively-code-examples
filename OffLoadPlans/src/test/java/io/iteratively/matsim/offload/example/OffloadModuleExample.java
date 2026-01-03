package io.iteratively.matsim.offload.example;

import io.iteratively.matsim.offload.OffloadConfigGroup;
import io.iteratively.matsim.offload.OffloadModule;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;

import java.io.File;
import java.net.URL;

/**
 * Example demonstrating how to use the Plan Offloading Module with PlanProxy architecture.
 * 
 * This example:
 * 1. Loads a standard MATSim scenario
 * 2. Configures the offload module with MapDB storage
 * 3. Runs a simulation where plans are kept as lightweight proxies
 * 4. Demonstrates memory-efficient plan management with full selector functionality
 */
public class OffloadModuleExample {

    public static void main(String[] args) {
        // Load a standard MATSim example scenario
        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
        Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(scenarioUrl, "config_default.xml"));

        // Configure the offload module
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        
        // Set directory for MapDB storage (will be created if it doesn't exist)
        File storeDir = new File("output/planstore");
        offloadConfig.setStoreDirectory(storeDir.getAbsolutePath());
        
        // Configure cache size (number of materialized plans to keep in memory)
        offloadConfig.setCacheEntries(2000);

        // Standard MATSim configuration
        config.controller().setOutputDirectory("output");
        config.controller().setOverwriteFileSetting(
            OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        
        // Run for 20 iterations
        config.controller().setLastIteration(20);
        
        // Limit plans per agent to 3 (excess plans will be removed)
        config.replanning().setMaxAgentPlanMemorySize(3);

        // Load scenario
        Scenario scenario = ScenarioUtils.loadScenario(config);

        System.out.println("Starting simulation with Plan Offloading Module");
        System.out.println("Store directory: " + storeDir.getAbsolutePath());
        System.out.println("Number of persons: " + scenario.getPopulation().getPersons().size());

        // Create controler and add the offload module
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());

        // Run the simulation
        controler.run();

        System.out.println("\nSimulation completed!");
        System.out.println("Plan store location: " + new File(storeDir, OffloadConfigGroup.MAPDB_FILE_NAME));
        
        // The MapDB file now contains all plans from all iterations
        // During simulation, all plans were kept as lightweight proxies with:
        // - All plan scores in memory (for proper selection)
        // - Only selected plans fully materialized
        // - Automatic dematerialization after iteration end
    }
    
    /**
     * Example showing how the PlanProxy architecture works internally:
     * 
     * <pre>
     * Iteration Start:
     *   1. OffloadSupport.loadAllPlansAsProxies(person, store)
     *      → Loads all plans as PlanProxy objects (scores in memory)
     *      → Each proxy: ~100 bytes vs ~10KB for full plan
     *   
     *   2. OffloadSupport.ensureSelectedMaterialized(person, store, cache)
     *      → Materializes only the selected plan for simulation
     * 
     * During Replanning:
     *   - ChangeExpBeta sees ALL plan proxies with scores
     *   - Compares exp(β × score) across all plans
     *   - Selects based on probabilities
     *   - Only selected plan is materialized
     * 
     * Iteration End:
     *   1. OffloadSupport.persistAllMaterialized(person, store, iter)
     *      → Saves only materialized + modified plans
     *      → Uses hash-based dirty checking
     *      → Dematerializes all plans (keeps proxies)
     *   
     *   2. Proxies remain in memory with scores
     *      → Ready for next iteration's selection
     * 
     * Memory footprint per agent (5 plans):
     *   - Full plans: 5 × 10KB = 50KB
     *   - Proxies only: 5 × 100 bytes = 500 bytes
     *   - Proxies + 1 materialized: 500 bytes + 10KB ≈ 10.5KB
     *   → 80% memory reduction while maintaining selector functionality
     * </pre>
     */
    public static void architectureExplanation() {
        // This is just documentation, see the main() method for actual usage
    }
}
