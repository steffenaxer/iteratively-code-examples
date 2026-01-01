package io.iteratively.matsim.offload;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mapdb.*;
import org.mapdb.serializer.SerializerCompressionWrapper;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Plan;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public final class MapDbPlanStore implements PlanStore {
    private static final Logger log = LogManager.getLogger(MapDbPlanStore.class);

    private final DB db;
    private final HTreeMap<String, byte[]> planBlobs;
    private final HTreeMap<String, Double> planScores;
    private final HTreeMap<String, Integer> planCreationIter;
    private final HTreeMap<String, Integer> planLastUsedIter;
    private final HTreeMap<String, String> activePlanByPerson;
    private final HTreeMap<String, String> planIndexByPerson;
    private final HTreeMap<String, String> planTypes;

    private final FuryPlanCodec codec;
    private final int maxPlansPerAgent;
    private final int batchSize;
    private int uncommittedWrites = 0;

    private final ConcurrentHashMap<String, List<String>> planIdCache;
    private final ConcurrentHashMap<String, String> activePlanCache;

    private final List<PendingWrite> pendingWrites = new ArrayList<>();
    private static final int WRITE_BUFFER_SIZE = 5000;

    private record PendingWrite(String personId, String planId, byte[] blob, double score, int iter, boolean makeSelected) {}

    public MapDbPlanStore(File file, Scenario scenario, int maxPlansPerAgent) {
        this(file, scenario, maxPlansPerAgent, 10_000);
    }

    public MapDbPlanStore(File file, Scenario scenario, int maxPlansPerAgent, int batchSize) {
        this.maxPlansPerAgent = maxPlansPerAgent;
        this.batchSize = batchSize;
        this.planIdCache = new ConcurrentHashMap<>();
        this.activePlanCache = new ConcurrentHashMap<>();

        this.db = DBMaker
                .fileDB(file)
                .fileMmapEnableIfSupported()
                .allocateStartSize(128 * 1024 * 1024)
                .allocateIncrement(16 * 1024 * 1024)
                .fileMmapPreclearDisable()
                .closeOnJvmShutdown()
                .make();

        this.planBlobs = db.hashMap("plans", Serializer.STRING,
                        new SerializerCompressionWrapper<>(Serializer.BYTE_ARRAY))
                .createOrOpen();
        this.planScores = db.hashMap("scores", Serializer.STRING, Serializer.DOUBLE).createOrOpen();
        this.planCreationIter = db.hashMap("creationIter", Serializer.STRING, Serializer.INTEGER).createOrOpen();
        this.planLastUsedIter = db.hashMap("lastUsedIter", Serializer.STRING, Serializer.INTEGER).createOrOpen();
        this.activePlanByPerson = db.hashMap("activePlan", Serializer.STRING, Serializer.STRING).createOrOpen();
        this.planIndexByPerson = db.hashMap("planIndex", Serializer.STRING, Serializer.STRING).createOrOpen();
        this.planTypes = db.hashMap("planTypes", Serializer.STRING, Serializer.STRING).createOrOpen();

        this.codec = new FuryPlanCodec(scenario.getPopulation().getFactory());
    }

    private static String key(String personId, String planId) {
        return personId + "|" + planId;
    }

    @Override
    public FuryPlanCodec getCodec() {
        return codec;
    }

    @Override
    public Optional<String> getActivePlanId(String personId) {
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
        byte[] blob = codec.serialize(plan);
        putPlanRaw(personId, planId, blob, score, iter, makeSelected);
        String planType = plan.getType();
        if (planType != null) planTypes.put(key(personId, planId), planType);
    }

    @Override
    public void putPlanRaw(String personId, String planId, byte[] blob, double score, int iter, boolean makeSelected) {
        synchronized (pendingWrites) {
            pendingWrites.add(new PendingWrite(personId, planId, blob, score, iter, makeSelected));

            updatePlanIndexCache(personId, planId);
            if (makeSelected) {
                activePlanCache.put(personId, planId);
            }

            if (pendingWrites.size() >= WRITE_BUFFER_SIZE) {
                flushPendingWrites();
            }
        }
    }

    private void updatePlanIndexCache(String personId, String planId) {
        planIdCache.compute(personId, (k, list) -> {
            if (list == null) list = loadPlanIds(personId);
            if (!list.contains(planId)) {
                List<String> newList = new ArrayList<>(list);
                newList.add(planId);
                return newList;
            }
            return list;
        });
    }

    private void flushPendingWrites() {
        if (pendingWrites.isEmpty()) return;

        log.info("Flushing {} pending writes to MapDB...", pendingWrites.size());
        long start = System.currentTimeMillis();

        Map<String, byte[]> blobBatch = new HashMap<>();
        Map<String, Double> scoreBatch = new HashMap<>();
        Map<String, Integer> creationBatch = new HashMap<>();
        Map<String, Integer> lastUsedBatch = new HashMap<>();
        Map<String, String> activeBatch = new HashMap<>();
        Set<String> affectedPersons = new HashSet<>();

        for (PendingWrite pw : pendingWrites) {
            String k = key(pw.personId, pw.planId);
            blobBatch.put(k, pw.blob);
            scoreBatch.put(k, pw.score);
            creationBatch.putIfAbsent(k, pw.iter);
            lastUsedBatch.put(k, pw.iter);
            if (pw.makeSelected) {
                activeBatch.put(pw.personId, pw.planId);
            }
            affectedPersons.add(pw.personId);
        }

        planBlobs.putAll(blobBatch);
        planScores.putAll(scoreBatch);
        planCreationIter.putAll(creationBatch);
        planLastUsedIter.putAll(lastUsedBatch);
        activePlanByPerson.putAll(activeBatch);

        for (String personId : affectedPersons) {
            List<String> currentIds = planIdCache.getOrDefault(personId, new ArrayList<>());
            planIndexByPerson.put(personId, String.join(",", currentIds));
        }

        pendingWrites.clear();
        uncommittedWrites = 0;

        log.info("Flush completed in {} ms", System.currentTimeMillis() - start);
    }

    private int enforcePlanLimitLazy(String personId) {
        List<String> ids = planIdCache.get(personId);
        if (ids == null || ids.size() <= maxPlansPerAgent) return 0;

        String activeId = activePlanCache.get(personId);

        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (String pid : ids) {
            if (!pid.equals(activeId)) {
                double score = planScores.getOrDefault(key(personId, pid), Double.NEGATIVE_INFINITY);
                scored.add(Map.entry(pid, score));
            }
        }

        scored.sort(Comparator.comparingDouble(Map.Entry::getValue));

        int toDelete = ids.size() - maxPlansPerAgent;
        int deleted = 0;
        for (int i = 0; i < toDelete && i < scored.size(); i++) {
            deletePlanInternal(personId, scored.get(i).getKey());
            deleted++;
        }
        return deleted;
    }

    private void enforceAllPlanLimits() {
        log.info("Enforcing plan limits for {} persons...", planIdCache.size());
        long start = System.currentTimeMillis();
        int deleted = 0;

        for (String personId : new ArrayList<>(planIdCache.keySet())) {
            deleted += enforcePlanLimitLazy(personId);
        }

        if (deleted > 0) {
            log.info("Deleted {} excess plans in {} ms", deleted, System.currentTimeMillis() - start);
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
            uncommittedWrites = 0;
        }
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
        synchronized (pendingWrites) {
            if (!pendingWrites.isEmpty()) {
                flushPendingWrites();
            }
        }

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
        deletePlanInternal(personId, planId);
        incrementUncommitted();
    }

    private void deletePlanInternal(String personId, String planId) {
        String k = key(personId, planId);
        planBlobs.remove(k);
        planScores.remove(k);
        planCreationIter.remove(k);
        planLastUsedIter.remove(k);
        planTypes.remove(k);

        planIdCache.computeIfPresent(personId, (key, list) -> {
            List<String> newList = new ArrayList<>(list);
            newList.remove(planId);
            if (newList.isEmpty()) {
                planIndexByPerson.remove(personId);
                return null;
            }
            planIndexByPerson.put(personId, String.join(",", newList));
            return newList;
        });

        if (planId.equals(activePlanCache.get(personId))) {
            activePlanCache.remove(personId);
            activePlanByPerson.remove(personId);
        }
    }

    @Override
    public void commit() {
        synchronized (pendingWrites) {
            flushPendingWrites();
        }
        uncommittedWrites = 0;
        enforceAllPlanLimits();
    }

    @Override
    public void close() {
        commit();
        db.close();
    }
}