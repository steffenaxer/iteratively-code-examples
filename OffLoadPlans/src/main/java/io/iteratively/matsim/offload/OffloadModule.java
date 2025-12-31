
package io.iteratively.matsim.offload;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.replanning.selectors.PlanSelector;

import java.io.File;

public final class OffloadModule extends AbstractModule {
    private final File dbFile;
    private final int cacheEntries;
    public OffloadModule(File dbFile, int cacheEntries) {
        this.dbFile = dbFile;
        this.cacheEntries = cacheEntries;
    }
    @Override public void install() {
        bind(PlanSelector.class).to(HeaderBasedPlanSelector.class).in(Singleton.class);
        addControllerListenerBinding().to(OffloadIterationHooks.class);
        addControllerListenerBinding().to(PlanStoreShutdownListener.class).in(Singleton.class);
    }

    @Provides @Singleton
    PlanStore providePlanStore(Scenario scenario) {
        return new MapDbPlanStore(dbFile, scenario);
    }
    @Provides @Singleton
    PlanCache providePlanCache(PlanStore store) {
        return new PlanCache(store, cacheEntries);
    }
    @Provides @Singleton
    HeaderBasedPlanSelector provideSelector(PlanStore store) {
        return new HeaderBasedPlanSelector(store);
    }
}
