package io.iteratively.matsim.offload;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;
import java.util.List;

public final class OffloadSupport {
    private static final Logger log = LogManager.getLogger(OffloadSupport.class);
    
    private OffloadSupport() {}

    public record PersistTask(String personId, String planId, byte[] blob, double score) {}

    public static boolean isValidScore(Double score) {
        return score != null && !score.isNaN() && !score.isInfinite();
    }

    public static double toStorableScore(Double score) {
        return isValidScore(score) ? score : Double.NEGATIVE_INFINITY;
    }

    public static void loadAllPlansAsProxies(Person p, PlanStore store) {
        String personId = p.getId().toString();
        List<PlanHeader> headers = store.listPlanHeaders(personId);

        if (headers.isEmpty()) {
            return;
        }

        p.getPlans().clear();

        Plan selectedPlan = null;
        for (PlanHeader h : headers) {
            PlanProxy proxy = new PlanProxy(h, p, store);
            p.addPlan(proxy);
            if (h.selected) {
                selectedPlan = proxy;
            }
        }

        if (selectedPlan != null) {
            p.setSelectedPlan(selectedPlan);
        } else if (!p.getPlans().isEmpty()) {
            p.setSelectedPlan(p.getPlans().get(0));
        }
    }

    public static void persistAllMaterialized(Person p, PlanStore store, int iter) {
        String personId = p.getId().toString();

        for (Plan plan : p.getPlans()) {
            if (plan instanceof PlanProxy proxy) {
                if (proxy.isMaterialized()) {
                    Plan materialized = proxy.getMaterializedPlan();
                    if (shouldPersist(materialized)) {
                        String planId = proxy.getPlanId();
                        double score = toStorableScore(proxy.getScore());
                        boolean isSelected = (plan == p.getSelectedPlan());
                        store.putPlan(personId, planId, materialized, score, iter, isSelected);
                        markPersisted(materialized);
                    }
                    proxy.dematerialize();
                }
            } else {
                if (shouldPersist(plan)) {
                    String planId = ensurePlanId(plan);
                    double score = toStorableScore(plan.getScore());
                    boolean isSelected = (plan == p.getSelectedPlan());
                    store.putPlan(personId, planId, plan, score, iter, isSelected);
                    markPersisted(plan);
                }
            }
        }
    }

    public static void addNewPlan(Person p, Plan plan, PlanStore store, int iter) {
        String personId = p.getId().toString();
        String planId = ensurePlanId(plan);
        double score = toStorableScore(plan.getScore());

        store.putPlan(personId, planId, plan, score, iter, false);
        markPersisted(plan);

        PlanProxy proxy = new PlanProxy(planId, p, store, plan.getType(), iter, plan.getScore());
        p.addPlan(proxy);
    }

    public static void ensureSelectedMaterialized(Person p, PlanStore store, PlanCache cache) {
        Plan selected = p.getSelectedPlan();
        if (selected == null) return;

        if (selected instanceof PlanProxy proxy) {
            proxy.getMaterializedPlan();
        }
    }

    public static void swapSelectedPlanTo(Person p, PlanStore store, String newPlanId) {
        String personId = p.getId().toString();

        for (Plan plan : p.getPlans()) {
            if (plan instanceof PlanProxy proxy) {
                if (proxy.getPlanId().equals(newPlanId)) {
                    p.setSelectedPlan(proxy);
                    store.setActivePlanId(personId, newPlanId);
                    return;
                }
            }
        }

        Plan newPlan = store.materialize(personId, newPlanId);
        Double score = store.listPlanHeaders(personId).stream()
                .filter(h -> h.planId.equals(newPlanId))
                .map(h -> h.score)
                .findFirst().orElse(null);
        newPlan.setScore(score);
        p.getPlans().clear();
        p.addPlan(newPlan);
        p.setSelectedPlan(newPlan);
        store.setActivePlanId(personId, newPlanId);
    }

    public static PersistTask preparePersist(Person p, FuryPlanCodec codec) {
        Plan sel = p.getSelectedPlan();
        if (sel == null || !shouldPersist(sel)) return null;

        String personId = p.getId().toString();
        String planId = ensurePlanId(sel);
        double score = toStorableScore(sel.getScore());
        byte[] blob = codec.serialize(sel);

        markPersisted(sel);
        return new PersistTask(personId, planId, blob, score);
    }

    public static void persistSelectedIfAny(Person p, PlanStore store, int iter) {
        Plan sel = p.getSelectedPlan();
        if (sel == null) return;

        String personId = p.getId().toString();
        String planId = ensurePlanId(sel);
        double score = toStorableScore(sel.getScore());

        if (shouldPersist(sel)) {
            store.putPlan(personId, planId, sel, score, iter, true);
            markPersisted(sel);
        }
    }

    private static boolean shouldPersist(Plan plan) {
        Object lastHash = plan.getAttributes().getAttribute("offloadLastHash");
        int currentHash = computePlanHash(plan);
        return lastHash == null || (int) lastHash != currentHash;
    }

    private static void markPersisted(Plan plan) {
        plan.getAttributes().putAttribute("offloadLastHash", computePlanHash(plan));
    }

    private static int computePlanHash(Plan plan) {
        int hash = plan.getPlanElements().size();
        Double score = plan.getScore();
        if (isValidScore(score)) {
            hash = 31 * hash + score.hashCode();
        }
        for (var element : plan.getPlanElements()) {
            hash = 31 * hash + element.hashCode();
        }
        return hash;
    }

    /**
     * Ensures that a plan has a unique ID. If the plan already has an ID attribute,
     * it returns that ID. Otherwise, it generates a new unique ID and stores it.
     * 
     * @param plan the plan to ensure has an ID
     * @return the plan's ID
     */
    public static String ensurePlanId(Plan plan) {
        Object attr = plan.getAttributes().getAttribute("offloadPlanId");
        if (attr instanceof String s) return s;
        String pid = "p" + System.nanoTime() + "_" + Math.abs(plan.hashCode());
        plan.getAttributes().putAttribute("offloadPlanId", pid);
        return pid;
    }
    
    /**
     * Loads a scenario using streaming population loading.
     * 
     * <p>This method provides a simple and intuitive way to load large populations
     * into MATSim without loading all plans into memory. It:</p>
     * <ol>
     *   <li>Saves the population file path from the config</li>
     *   <li>Temporarily sets the population file to null</li>
     *   <li>Loads the scenario (without population)</li>
     *   <li>Creates a plan store in outputdir/planstore (or custom location)</li>
     *   <li>Uses streaming to load all plans into the store</li>
     *   <li>Loads all plans as proxies into the scenario</li>
     *   <li>Ensures selected plans are materialized</li>
     * </ol>
     * 
     * <p>After this method returns, the scenario is ready with:</p>
     * <ul>
     *   <li>Persons with all plans as proxies in memory</li>
     *   <li>Selected plans materialized</li>
     *   <li>All plans stored in the plan store</li>
     * </ul>
     * 
     * <p>Usage example:</p>
     * <pre>{@code
     * Config config = ConfigUtils.loadConfig("config.xml");
     * config.plans().setInputFile("population.xml.gz");
     * config.controller().setOutputDirectory("output");
     * 
     * // Optional: configure offload settings
     * OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
     * offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
     * // If not specified, plan store will be created in output/planstore
     * 
     * Scenario scenario = OffloadSupport.loadScenarioWithStreaming(config);
     * 
     * Controler controler = new Controler(scenario);
     * controler.addOverridingModule(new OffloadModule());
     * controler.run();
     * }</pre>
     * 
     * @param config the MATSim config with population file specified
     * @return a scenario with streaming-loaded population
     * @throws IllegalArgumentException if no population file is specified
     */
    public static Scenario loadScenarioWithStreaming(Config config) {
        // Get the population file path and make it absolute
        String populationFile = config.plans().getInputFile();
        
        if (populationFile == null || populationFile.isEmpty()) {
            throw new IllegalArgumentException(
                "No population file specified in config. " +
                "Please set config.plans().setInputFile(...) before calling this method.");
        }
        
        // Resolve to absolute path if it's relative (MATSim context is needed)
        String absolutePopulationFile;
        if (new File(populationFile).isAbsolute()) {
            absolutePopulationFile = populationFile;
        } else {
            // Get the context from config if available
            String context = config.getContext() != null ? config.getContext().toString() : null;
            if (context != null) {
                try {
                    absolutePopulationFile = org.matsim.core.utils.io.IOUtils.extendUrl(
                        new java.net.URL(context), populationFile).toString();
                } catch (Exception e) {
                    // If URL construction fails, try as file
                    File contextFile = new File(context).getParentFile();
                    absolutePopulationFile = new File(contextFile, populationFile).getAbsolutePath();
                }
            } else {
                // No context, use as is and hope it works
                absolutePopulationFile = new File(populationFile).getAbsolutePath();
            }
        }
        
        log.info("Loading scenario with streaming population loading from: {}", absolutePopulationFile);
        
        // Temporarily set to null to prevent loading during scenario creation
        config.plans().setInputFile(null);
        
        // Load scenario without population
        Scenario scenario = ScenarioUtils.loadScenario(config);
        
        // Get offload configuration
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        int maxPlans = config.replanning().getMaxAgentPlanMemorySize();
        
        // Determine plan store directory
        File baseDir = determineStoreDirectory(config, offloadConfig);
        baseDir.mkdirs();
        
        // Create plan store
        PlanStore planStore = createPlanStore(offloadConfig.getStorageBackend(), baseDir, scenario, maxPlans);
        
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
            loader.loadFromFile(absolutePopulationFile);
            
            log.info("Loading plans as proxies into scenario...");
            // Load all plans as proxies into the scenario
            scenario.getPopulation().getPersons().values().forEach(person -> {
                loadAllPlansAsProxies(person, planStore);
            });
            
            log.info("Materializing selected plans...");
            // Ensure selected plans are materialized
            scenario.getPopulation().getPersons().values().forEach(person -> {
                ensureSelectedMaterialized(person, planStore, planCache);
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
    
    /**
     * Determines the store directory based on configuration.
     * If not explicitly set, uses outputDirectory/planstore.
     */
    private static File determineStoreDirectory(Config config, OffloadConfigGroup offloadConfig) {
        if (offloadConfig.getStoreDirectory() != null) {
            return new File(offloadConfig.getStoreDirectory());
        }
        
        // Default: use output directory with planstore subfolder
        String outputDir = config.controller().getOutputDirectory();
        if (outputDir != null && !outputDir.isEmpty()) {
            return new File(outputDir, "planstore");
        }
        
        // Fallback: use temp directory
        return new File(System.getProperty("java.io.tmpdir"),
                "matsim-offload-" + System.currentTimeMillis());
    }
    
    /**
     * Creates a plan store with unified naming conventions.
     */
    private static PlanStore createPlanStore(OffloadConfigGroup.StorageBackend backend, 
                                             File baseDir, Scenario scenario, int maxPlans) {
        return switch (backend) {
            case MAPDB -> {
                File dbFile = new File(baseDir, OffloadConfigGroup.MAPDB_FILE_NAME);
                yield new MapDbPlanStore(dbFile, scenario, maxPlans);
            }
            case ROCKSDB -> {
                File rocksDir = new File(baseDir, OffloadConfigGroup.ROCKSDB_DIR_NAME);
                rocksDir.mkdirs();
                yield new RocksDbPlanStore(rocksDir, scenario, maxPlans);
            }
        };
    }
}
