package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;

import java.util.List;
import java.util.Objects;

public final class OffloadIterationHooks implements IterationStartsListener, IterationEndsListener {
    private static final Logger log = LogManager.getLogger(OffloadIterationHooks.class);

    private final PlanStore store;
    private final PlanCache cache;

    @Inject
    public OffloadIterationHooks(PlanStore store, PlanCache cache) {
        this.store = store;
        this.cache = cache;
    }

    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        var pop = event.getServices().getScenario().getPopulation();
        for (Person p : pop.getPersons().values()) {
            OffloadSupport.ensureSelectedMaterialized(p, store, cache);
        }
        store.commit();
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        int iter = event.getIteration();
        var pop = event.getServices().getScenario().getPopulation();
        FuryPlanCodec codec = store.getCodec();

        log.info("Iteration {}: Starting plan serialization...", iter);

        // Parallel: Hash-Check + Serialisierung
        List<OffloadSupport.PersistTask> tasks = pop.getPersons().values().parallelStream()
                .map(p -> OffloadSupport.preparePersist(p, codec))
                .filter(Objects::nonNull)
                .toList();

        log.info("Iteration {}: {} plans to persist", iter, tasks.size());

        // Sequentiell: DB-Writes mit Progress-Logging
        int totalTasks = tasks.size();
        int logInterval = Math.max(1, totalTasks / 10); // Alle 10% loggen

        for (int i = 0; i < totalTasks; i++) {
            OffloadSupport.PersistTask task = tasks.get(i);
            store.putPlanRaw(task.personId(), task.planId(), task.blob(), task.score(), iter, true);

            if ((i + 1) % logInterval == 0 || i == totalTasks - 1) {
                int percent = (int) ((i + 1) * 100.0 / totalTasks);
                log.info("Iteration {}: Persisting plans {}/{} ({}%)", iter, i + 1, totalTasks, percent);
            }
        }

        log.info("Iteration {}: Cleaning up in-memory plans...", iter);

        // Cleanup
        for (Person p : pop.getPersons().values()) {
            p.getPlans().clear();
        }

        store.commit();
        cache.evictAll();

        log.info("Iteration {}: Plan offload completed", iter);
    }
}