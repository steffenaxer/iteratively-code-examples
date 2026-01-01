package io.iteratively.matsim.offload;

import org.mapdb.*;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Plan;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public final class MapDbPlanStore implements PlanStore {
    private final DB db;
    private final HTreeMap<String, byte[]> planBlobs;
    private final HTreeMap<String, Double> planScores;
    private final HTreeMap<String, Integer> planCreationIter;
    private final HTreeMap<String, Integer> planLastUsedIter;
    private final HTreeMap<String, String> activePlanByPerson;
    private final HTreeMap<String, String> planIndexByPerson;
    private final HTreeMap<String, String> planTypes;

    private final org.matsim.api.core.v01.population.PopulationFactory populationFactory;
    private final FuryPlanCodec codec;
    private final int maxPlansPerAgent;

    // Async-Write Support
    private final ExecutorService writeExecutor;
    private final BlockingQueue<Runnable> pendingWrites;
    private final int batchSize;
    private int uncommittedWrites = 0;

    // Cache für häufig gelesene Daten
    private final ConcurrentHashMap<String, List<String>> planIdCache;
    private final ConcurrentHashMap<String, String> activePlanCache;

    public MapDbPlanStore(File file, Scenario scenario, int maxPlansPerAgent) {
        this(file, scenario, maxPlansPerAgent, 100); // Default batch size
    }

    public MapDbPlanStore(File file, Scenario scenario, int maxPlansPerAgent, int batchSize) {
        this.maxPlansPerAgent = maxPlansPerAgent;
        this.batchSize = batchSize;
        this.pendingWrites = new LinkedBlockingQueue<>();
        this.writeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MapDbPlanStore-Writer");
            t.setDaemon(true);
            return t;
        });

        this.planIdCache = new ConcurrentHashMap<>();
        this.activePlanCache = new ConcurrentHashMap<>();

        this.db = DBMaker
                .fileDB(file)
                .fileMmapEnableIfSupported()
                .transactionEnable()
                .closeOnJvmShutdown()
                .make();

        this.planBlobs = db.hashMap("plans", Serializer.STRING, Serializer.BYTE_ARRAY).createOrOpen();
        this.planScores = db.hashMap("scores", Serializer.STRING, Serializer.DOUBLE).createOrOpen();
        this.planCreationIter = db.hashMap("creationIter", Serializer.STRING, Serializer.INTEGER).createOrOpen();
        this.planLastUsedIter = db.hashMap("lastUsedIter", Serializer.STRING, Serializer.INTEGER).createOrOpen();
        this.activePlanByPerson = db.hashMap("activePlan", Serializer.STRING, Serializer.STRING).createOrOpen();
        this.planIndexByPerson = db.hashMap("planIndex", Serializer.STRING, Serializer.STRING).createOrOpen();
        this.planTypes = db.hashMap("planTypes", Serializer.STRING, Serializer.STRING).createOrOpen();

        this.populationFactory = scenario.getPopulation().getFactory();
        this.codec = new FuryPlanCodec(populationFactory);
    }

    private static String key(String personId, String planId) {
        return personId + "|" + planId;
    }

    @Override
    public Optional<String> getActivePlanId(String personId) {
        // Cache-First
        String cached = activePlanCache.get(personId);
        if (cached != null) return Optional.of(cached);
        String active = activePlanByPerson.get(personId);
        if (active != null) activePlanCache.put(personId, active);
        return Optional.ofNullable(active);
    }

    @Override
    public void setActivePlanId(String personId, String planId) {
        activePlanCache.put(personId, planId);
        activePlanByPerson.put(personId, planId);
        incrementUncommitted();
    }

    @Override
    public List<PlanHeader> listPlanHeaders(String personId) {
        List<String> ids = listPlanIds(personId);
        if (ids.isEmpty()) return List.of();

        String activeId = activePlanCache.get(personId);
        if (activeId == null) activeId = activePlanByPerson.get(personId);

        List<PlanHeader> out = new ArrayList<>(ids.size());
        for (String pid : ids) {
            String k = key(personId, pid);
            double score = planScores.getOrDefault(k, Double.NEGATIVE_INFINITY);
            String type = planTypes.get(k);
            int cIter = planCreationIter.getOrDefault(k, -1);
            int lIter = planLastUsedIter.getOrDefault(k, -1);
            boolean sel = pid.equals(activeId);
            out.add(new PlanHeader(pid, score, type, cIter, lIter, sel));
        }
        return out;
    }

    @Override
    public void putPlan(String personId, String planId, Plan plan, double score, int iter, boolean makeSelected) {
        // Serialisierung im Caller-Thread (kann parallelisiert werden)
        byte[] blob = codec.serialize(plan);
        String planType = plan.getType();

        // DB-Writes
        String k = key(personId, planId);
        planBlobs.put(k, blob);
        planScores.put(k, score);
        if (planType != null) planTypes.put(k, planType);
        planCreationIter.putIfAbsent(k, iter);
        planLastUsedIter.put(k, iter);

        // Plan-Index aktualisieren (mit Cache)
        updatePlanIndex(personId, planId);

        if (makeSelected) {
            activePlanCache.put(personId, planId);
            activePlanByPerson.put(personId, planId);
        }

        incrementUncommitted();
        enforcePlanLimit(personId);
    }

    /**
     * Async-Variante für nicht-blockierendes Schreiben.
     */
    public void putPlanAsync(String personId, String planId, Plan plan, double score, int iter, boolean makeSelected) {
        // Serialisierung sofort (Plan könnte sich ändern)
        byte[] blob = codec.serialize(plan);
        String planType = plan.getType();

        writeExecutor.submit(() -> {
            String k = key(personId, planId);
            planBlobs.put(k, blob);
            planScores.put(k, score);
            if (planType != null) planTypes.put(k, planType);
            planCreationIter.putIfAbsent(k, iter);
            planLastUsedIter.put(k, iter);
            updatePlanIndex(personId, planId);
            if (makeSelected) {
                activePlanCache.put(personId, planId);
                activePlanByPerson.put(personId, planId);
            }
            incrementUncommitted();
            enforcePlanLimit(personId);
        });
    }

    private synchronized void updatePlanIndex(String personId, String planId) {
        List<String> list = planIdCache.computeIfAbsent(personId, this::loadPlanIds);
        if (!list.contains(planId)) {
            List<String> newList = new ArrayList<>(list);
            newList.add(planId);
            planIdCache.put(personId, newList);
            planIndexByPerson.put(personId, String.join(",", newList));
        }
    }

    private List<String> loadPlanIds(String personId) {
        String csv = planIndexByPerson.get(personId);
        if (csv == null || csv.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(csv.split(",")));
    }

    private synchronized void incrementUncommitted() {
        uncommittedWrites++;
        if (uncommittedWrites >= batchSize) {
            db.commit();
            uncommittedWrites = 0;
        }
    }

    private void enforcePlanLimit(String personId) {
        List<PlanHeader> headers = listPlanHeaders(personId);
        if (headers.size() <= maxPlansPerAgent) return;

        String activeId = activePlanCache.getOrDefault(personId, activePlanByPerson.get(personId));
        headers.stream()
                .filter(h -> !h.planId.equals(activeId))
                .sorted(Comparator.comparingDouble(h -> h.score))
                .limit(headers.size() - maxPlansPerAgent)
                .forEach(h -> deletePlan(personId, h.planId));
    }

    @Override
    public void updateScore(String personId, String planId, double score, int iter) {
        String k = key(personId, planId);
        planScores.put(k, score);
        planLastUsedIter.put(k, iter);
        incrementUncommitted();
    }

    @Override
    public Plan materialize(String personId, String planId) {
        String k = key(personId, planId);
        byte[] blob = planBlobs.get(k);
        if (blob == null) throw new IllegalStateException("Plan blob missing for " + k);
        return codec.deserialize(blob);
    }

    @Override
    public boolean hasPlan(String personId, String planId) {
        return planBlobs.containsKey(key(personId, planId));
    }

    @Override
    public List<String> listPlanIds(String personId) {
        return planIdCache.computeIfAbsent(personId, this::loadPlanIds);
    }

    @Override
    public synchronized void deletePlan(String personId, String planId) {
        String k = key(personId, planId);
        planBlobs.remove(k);
        planScores.remove(k);
        planCreationIter.remove(k);
        planLastUsedIter.remove(k);
        planTypes.remove(k);

        // Cache und Index aktualisieren
        List<String> list = new ArrayList<>(listPlanIds(personId));
        list.remove(planId);
        if (list.isEmpty()) {
            planIndexByPerson.remove(personId);
            planIdCache.remove(personId);
        } else {
            planIndexByPerson.put(personId, String.join(",", list));
            planIdCache.put(personId, list);
        }

        if (planId.equals(activePlanCache.get(personId))) {
            activePlanCache.remove(personId);
            activePlanByPerson.remove(personId);
        }
        incrementUncommitted();
    }

    /**
     * Wartet auf alle ausstehenden async Writes.
     */
    public void flushAsync() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        writeExecutor.submit(latch::countDown);
        latch.await();
    }

    @Override
    public void commit() {
        db.commit();
        uncommittedWrites = 0;
    }

    @Override
    public void close() {
        writeExecutor.shutdown();
        try {
            writeExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.commit();
        db.close();
    }
}