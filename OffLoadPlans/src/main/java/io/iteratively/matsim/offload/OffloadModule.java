package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.replanning.selectors.*;

import java.io.File;

public final class OffloadModule extends AbstractModule {

    @Override
    public void install() {
        addControllerListenerBinding().to(OffloadIterationHooks.class);
        addControllerListenerBinding().to(PlanStoreShutdownListener.class).in(Singleton.class);
        bindPlanSelectorForRemoval().toProvider(OffloadPlanSelectorProvider.class).in(Singleton.class);
    }

    @Provides
    @Singleton
    PlanStore providePlanStore(Scenario scenario) {
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(
                scenario.getConfig(), OffloadConfigGroup.class);
        int maxPlans = scenario.getConfig().replanning().getMaxAgentPlanMemorySize();

        // Determine base directory using same logic as OffloadSupport
        File baseDir = determineStoreDirectory(scenario.getConfig(), offloadConfig);
        baseDir.mkdirs();

        return switch (offloadConfig.getStorageBackend()) {
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

    @Provides
    @Singleton
    PlanCache providePlanCache(PlanStore store, Scenario scenario) {
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(
                scenario.getConfig(), OffloadConfigGroup.class);
        return new PlanCache(store, offloadConfig.getCacheEntries());
    }

    static class OffloadPlanSelectorProvider implements Provider<PlanSelector<Plan, Person>> {
        @Inject Scenario scenario;
        @Inject PlanStore store;
        @Inject MatsimServices matsimServices;

        @Override
        public PlanSelector<Plan, Person> get() {
            // Hole den konfigurierten Selector-Namen und erstelle die MATSim-Standard-Instanz
            String selectorName = scenario.getConfig().replanning().getPlanSelectorForRemoval();
            PlanSelector<Plan, Person> delegate = createDelegateSelector(selectorName);

            return (member) -> {
                int currentIteration = matsimServices.getIterationNumber();
                LazyOffloadPlanSelector selector = new LazyOffloadPlanSelector(delegate, store, currentIteration);
                return selector.selectPlan(member);
            };
        }

        private PlanSelector<Plan, Person> createDelegateSelector(String name) {
            return switch (name) {
                case "RandomPlanSelector" -> new RandomPlanSelector<>();
                case "BestPlanSelector" -> new BestPlanSelector<>();
                case "ExpBetaPlanSelector" -> new ExpBetaPlanSelector<>(scenario.getConfig().scoring());
                case "WorstPlanSelector" -> new GenericWorstPlanForRemovalSelector<>();
                default -> new GenericWorstPlanForRemovalSelector<>();
            };
        }
    }
}
