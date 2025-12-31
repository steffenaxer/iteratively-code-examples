package io.iteratively.matsim.offload;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.replanning.selectors.PlanSelector;

import java.io.File;

public final class OffloadModule extends AbstractModule {

    @Override
    public void install() {
        bind(PlanSelector.class).to(HeaderBasedPlanSelector.class).in(Singleton.class);
        addControllerListenerBinding().to(OffloadIterationHooks.class);
        addControllerListenerBinding().to(PlanStoreShutdownListener.class).in(Singleton.class);
    }

    @Provides
    @Singleton
    PlanStore providePlanStore(Scenario scenario) {
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(
                scenario.getConfig(), OffloadConfigGroup.class);
        int maxPlans = scenario.getConfig().replanning().getMaxAgentPlanMemorySize();

        File baseDir;
        if (offloadConfig.getStoreDirectory() != null) {
            baseDir = new File(offloadConfig.getStoreDirectory());
        } else {
            baseDir = new File(System.getProperty("java.io.tmpdir"),
                    "matsim-offload-" + System.currentTimeMillis());
        }
        baseDir.mkdirs();

        File dbFile = new File(baseDir, OffloadConfigGroup.DB_FILE_NAME);
        return new MapDbPlanStore(dbFile, scenario, maxPlans);
    }

    @Provides
    @Singleton
    PlanCache providePlanCache(PlanStore store, Scenario scenario) {
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(
                scenario.getConfig(), OffloadConfigGroup.class);
        return new PlanCache(store, offloadConfig.getCacheEntries());
    }

    @Provides
    @Singleton
    HeaderBasedPlanSelector provideSelector(PlanStore store) {
        return new HeaderBasedPlanSelector(store);
    }
}
