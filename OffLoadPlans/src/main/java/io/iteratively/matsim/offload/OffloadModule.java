package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
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
        addControllerListenerBinding().to(AfterReplanningDematerializer.class);
        addMobsimListenerBinding().to(MobsimPlanMaterializationMonitor.class);
    }

    @Provides
    @Singleton
    PlanStore providePlanStore(Scenario scenario) {
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(
                scenario.getConfig(), OffloadConfigGroup.class);

        File baseDir;
        if (offloadConfig.getStoreDirectory() != null) {
            baseDir = new File(offloadConfig.getStoreDirectory());
        } else {
            baseDir = new File(System.getProperty("java.io.tmpdir"),
                    "matsim-offload-" + System.currentTimeMillis());
        }
        baseDir.mkdirs();

        return switch (offloadConfig.getStorageBackend()) {
            case MAPDB -> {
                File dbFile = new File(baseDir, OffloadConfigGroup.DB_FILE_NAME);
                yield new MapDbPlanStore(dbFile, scenario);
            }
            case ROCKSDB -> {
                File rocksDir = new File(baseDir, "rocksdb");
                rocksDir.mkdirs();
                yield new RocksDbPlanStore(rocksDir, scenario);
            }
        };
    }
}
