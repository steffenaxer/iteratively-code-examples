package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;

public final class OffloadIterationHooks implements IterationStartsListener, IterationEndsListener {
    private static final Logger log = LogManager.getLogger(OffloadIterationHooks.class);

    private final PlanStore store;
    private final PlanCache cache;
    private final OffloadConfigGroup config;

    @Inject
    public OffloadIterationHooks(PlanStore store, PlanCache cache, OffloadConfigGroup config) {
        this.store = store;
        this.cache = cache;
        this.config = config;
    }

    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        int iter = event.getIteration();
        var pop = event.getServices().getScenario().getPopulation();

        // Bei Iteration 0: Initiale Pläne zuerst im Store speichern
        if (iter == 0) {
            log.info("Iteration 0: Persisting initial plans to store...");
            for (Person p : pop.getPersons().values()) {
                OffloadSupport.persistAllMaterialized(p, store, iter);
            }
            store.commit();
            log.info("Iteration 0: Initial plans persisted");
        }

        // Load all plans as proxies for all persons
        for (Person p : pop.getPersons().values()) {
            OffloadSupport.loadAllPlansAsProxies(p, store);
        }

        // Ensure selected plan is materialized for simulation
        for (Person p : pop.getPersons().values()) {
            OffloadSupport.ensureSelectedMaterialized(p, store, cache);
        }

        // Auto-dematerialize non-selected plans if enabled
        if (config.isEnableAutodematerialization()) {
            PlanMaterializationMonitor.dematerializeAllNonSelected(pop);
        }

        // Log materialization statistics if enabled
        if (config.isLogMaterializationStats()) {
            PlanMaterializationMonitor.logStats(pop, "iteration " + iter + " start");
        }

        store.commit();
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        int iter = event.getIteration();
        var pop = event.getServices().getScenario().getPopulation();

        log.info("Iteration {}: Starting plan persistence...", iter);

        for (Person p : pop.getPersons().values()) {
            OffloadSupport.persistAllMaterialized(p, store, iter);
        }

        store.commit();
        cache.evictAll();

        // Auto-dematerialize non-selected plans if enabled
        if (config.isEnableAutodematerialization()) {
            PlanMaterializationMonitor.dematerializeAllNonSelected(pop);
        }

        // Log materialization statistics if enabled
        if (config.isLogMaterializationStats()) {
            PlanMaterializationMonitor.logStats(pop, "iteration " + iter + " end");
        }

        log.info("Iteration {}: Plan offload completed", iter);
    }
}
