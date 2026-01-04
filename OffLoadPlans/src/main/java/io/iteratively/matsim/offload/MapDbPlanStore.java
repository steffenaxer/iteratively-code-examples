package io.iteratively.matsim.offload;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mapdb.*;
import org.mapdb.serializer.SerializerCompressionWrapper;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public final class MapDbPlanStore implements PlanStore {
    private static final Logger log = LogManager.getLogger(MapDbPlanStore.class);
    private static final Pattern COMMA_PATTERN = Pattern.compile(",");

    private final DB db;
    private final HTreeMap<String, byte[]> planDataMap;  // Konsolidiert: blob + metadata
    private final HTreeMap<String, String> activePlanByPerson;
    private final HTreeMap<String, String> planIndexByPerson;

    private final FuryPlanCodec codec;
    private final int maxPlansPerAgent;
    private final Scenario scenario;

    private final ConcurrentHashMap<String, List<String>> planIdCache;
    private final ConcurrentHashMap<String, String> activePlanCache;
    private final ConcurrentHashMap<String, Integer> creationIterCache;

    private final List<PendingWrite> pendingWrites = new ArrayList<>();
    private static final int WRITE_BUFFER_SIZE = 100_000;

    private record PlanData(byte[] blob, double score, int creationIter, int lastUsedIter, String type) implements Serializable {
        static byte[] serializeDirect(byte[] blob, double score, int creationIter, int lastUsedIter, String type) {
            try {
                int estimatedSize = 4 + blob.length + 8 + 4 + 4 + 1 + (type != null ? 4 + type.length() * 3 : 0);
                ByteArrayOutputStream baos = new ByteArrayOutputStream(estimatedSize);
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeInt(blob.length);
                dos.write(blob);
                dos.writeDouble(score);
                dos.writeInt(creationIter);
                dos.writeInt(lastUsedIter);
                dos.writeBoolean(type != null);
                if (type != null) dos.writeUTF(type);
                dos.flush();
                return baos.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        
        byte[] serialize() {
            return serializeDirect(blob, score, creationIter, lastUsedIter, type);
        }

        static PlanData deserialize(byte[] data) {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                 DataInputStream dis = new DataInputStream(bais)) {
                int blobLen = dis.readInt();
                byte[] blob = new byte[blobLen];
                dis.readFully(blob);
                double score = dis.readDouble();
                int creationIter = dis.readInt();
                int lastUsedIter = dis.readInt();
                String type = dis.readBoolean() ? dis.readUTF() : null;
                return new PlanData(blob, score, creationIter, lastUsedIter, type);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private record PendingWrite(String key, String personId, String planId, byte[] blob, double score, int iter, boolean makeSelected, String type) {
        static PendingWrite create(String personId, String planId, byte[] blob, double score, int iter, boolean makeSelected, String type) {
            return new PendingWrite(MapDbPlanStore.key(personId, planId), personId, planId, blob, score, iter, makeSelected, type);
        }
    }

    public MapDbPlanStore(File file, Scenario scenario, int maxPlansPerAgent) {
        this.maxPlansPerAgent = maxPlansPerAgent;
        this.scenario = scenario;
        this.planIdCache = new ConcurrentHashMap<>();
        this.activePlanCache = new ConcurrentHashMap<>();
        this.creationIterCache = new ConcurrentHashMap<>();

        this.db = DBMaker
                .fileDB(file)
                .fileMmapEnableIfSupported()
                .allocateStartSize(256 * 1024 * 1024)
                .allocateIncrement(128 * 1024 * 1024)
                .fileMmapPreclearDisable()
                .transactionEnable()
                .executorEnable()
                .closeOnJvmShutdown()
                .make();

        this.planDataMap = db.hashMap("planData", Serializer.STRING,
                        new SerializerCompressionWrapper<>(Serializer.BYTE_ARRAY))
                .createOrOpen();
        this.activePlanByPerson = db.hashMap("activePlan", Serializer.STRING, Serializer.STRING).createOrOpen();
        this.planIndexByPerson = db.hashMap("planIndex", Serializer.STRING, Serializer.STRING).createOrOpen();

        this.codec = new FuryPlanCodec(scenario.getPopulation().getFactory());
    }

    private static String key(String personId, String planId) {
        return personId + "|" + planId;
    }

    private static String joinPlanIds(List<String> ids) {
        if (ids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(ids.size() * 12);
        sb.append(ids.get(0));
        for (int i = 1; i < ids.size(); i++) {
            sb.append(',').append(ids.get(i));
        }
        return sb.toString();
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
    }

    @Override
    public List<PlanProxy> listPlanProxies(Person person) {
        String personId = person.getId().toString();
        List<String> ids = listPlanIds(personId);
        if (ids.isEmpty()) return List.of();

        String activeId = activePlanCache.get(personId);
        if (activeId == null) activeId = activePlanByPerson.get(personId);

        List<PlanProxy> out = new ArrayList<>(ids.size());
        for (String pid : ids) {
            PlanData data = getPlanData(personId, pid);
            if (data != null) {
                boolean sel = pid.equals(activeId);
                PlanProxy proxy = new PlanProxy(pid, person, this, data.type, data.creationIter, data.score, sel);
                out.add(proxy);
            }
        }
        return out;
    }

    private PlanData getPlanData(String personId, String planId) {
        // Erst in pending writes suchen
        synchronized (pendingWrites) {
            for (int i = pendingWrites.size() - 1; i >= 0; i--) {
                PendingWrite pw = pendingWrites.get(i);
                if (pw.personId.equals(personId) && pw.planId.equals(planId)) {
                    return new PlanData(pw.blob, pw.score, pw.iter, pw.iter, pw.type);
                }
            }
        }
        byte[] raw = planDataMap.get(key(personId, planId));
        return raw != null ? PlanData.deserialize(raw) : null;
    }

    @Override
    public void putPlan(String personId, String planId, Plan plan, double score, int iter, boolean makeSelected) {
        byte[] blob = codec.serialize(plan);
        String planType = plan.getType();
        putPlanRaw(personId, planId, blob, score, iter, makeSelected, planType);
    }

    @Override
    public void putPlanRaw(String personId, String planId, byte[] blob, double score, int iter, boolean makeSelected) {
        putPlanRaw(personId, planId, blob, score, iter, makeSelected, null);
    }
    
    private void putPlanRaw(String personId, String planId, byte[] blob, double score, int iter, boolean makeSelected, String planType) {
        String k = key(personId, planId);
        creationIterCache.putIfAbsent(k, iter);
        
        boolean shouldFlush;
        synchronized (pendingWrites) {
            pendingWrites.add(PendingWrite.create(personId, planId, blob, score, iter, makeSelected, planType));
            updatePlanIndexCache(personId, planId);
            if (makeSelected) {
                activePlanCache.put(personId, planId);
            }
            shouldFlush = pendingWrites.size() >= WRITE_BUFFER_SIZE;
        }
        
        if (shouldFlush) {
            flushPendingWrites();
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

        int size = pendingWrites.size();
        Map<String, byte[]> dataBatch = new HashMap<>(size);
        Map<String, String> activeBatch = new HashMap<>(size / 4 + 1);
        Set<String> affectedPersons = new HashSet<>(size / 2 + 1);

        Map<String, PendingWrite> latestByKey = new LinkedHashMap<>(size);
        for (PendingWrite pw : pendingWrites) {
            latestByKey.put(pw.key, pw);
            if (pw.makeSelected) {
                activeBatch.put(pw.personId, pw.planId);
            }
            affectedPersons.add(pw.personId);
        }

        for (Map.Entry<String, PendingWrite> entry : latestByKey.entrySet()) {
            PendingWrite pw = entry.getValue();
            int creationIter = creationIterCache.getOrDefault(pw.key, pw.iter);
            byte[] serialized = PlanData.serializeDirect(pw.blob, pw.score, creationIter, pw.iter, pw.type);
            dataBatch.put(pw.key, serialized);
        }

        planDataMap.putAll(dataBatch);
        if (!activeBatch.isEmpty()) {
            activePlanByPerson.putAll(activeBatch);
        }

        Map<String, String> indexBatch = new HashMap<>(affectedPersons.size());
        for (String personId : affectedPersons) {
            List<String> currentIds = planIdCache.getOrDefault(personId, new ArrayList<>());
            indexBatch.put(personId, joinPlanIds(currentIds));
        }
        if (!indexBatch.isEmpty()) {
            planIndexByPerson.putAll(indexBatch);
        }

        db.commit();

        pendingWrites.clear();

        log.info("Flush completed in {} ms (wrote {} plans)", System.currentTimeMillis() - start, latestByKey.size());
    }

    /**
     * Enforces plan limit for a specific person based on their actual plan list.
     * This ensures tight coupling between the Person's plans and PlanStore.
     * The position/index in the Person's plan list acts as a unique identifier
     * together with the personId.
     */
    private int enforcePlanLimitForPerson(Person person, String personId) {
        List<Plan> plans = person.getPlans();
        if (plans.size() <= maxPlansPerAgent) return 0;

        Plan selectedPlan = person.getSelectedPlan();

        // Collect all non-selected plans with their scores
        List<Map.Entry<Plan, Double>> scored = new ArrayList<>(plans.size());
        for (Plan plan : plans) {
            if (plan != selectedPlan) {
                Double score = plan.getScore();
                double scoreValue = (score != null && !score.isNaN() && !score.isInfinite()) 
                    ? score : Double.NEGATIVE_INFINITY;
                scored.add(Map.entry(plan, scoreValue));
            }
        }

        // Sort by score (lowest first, will be deleted)
        scored.sort(Comparator.comparingDouble(Map.Entry::getValue));

        int toDelete = plans.size() - maxPlansPerAgent;
        int deleted = 0;
        
        // Delete lowest-scoring plans
        for (int i = 0; i < toDelete && i < scored.size(); i++) {
            Plan planToDelete = scored.get(i).getKey();
            
            // Get planId from the plan (works for both PlanProxy and regular Plan)
            String planId;
            if (planToDelete instanceof PlanProxy proxy) {
                planId = proxy.getPlanId();
            } else {
                // For regular plans, get the planId from attributes
                Object attr = planToDelete.getAttributes().getAttribute("offloadPlanId");
                if (attr instanceof String s) {
                    planId = s;
                } else {
                    continue; // Skip plans without planId
                }
            }
            
            deletePlanInternal(personId, planId);
            deleted++;
        }
        
        return deleted;
    }

    private void enforceAllPlanLimits() {
        var population = scenario.getPopulation();
        log.info("Enforcing plan limits for {} persons...", population.getPersons().size());
        long start = System.currentTimeMillis();
        int deleted = 0;

        // Iterate over actual persons in the scenario to ensure tight coupling
        // between Person's plan list and PlanStore
        for (Person person : population.getPersons().values()) {
            String personId = person.getId().toString();
            deleted += enforcePlanLimitForPerson(person, personId);
        }

        if (deleted > 0) {
            log.info("Deleted {} excess plans in {} ms", deleted, System.currentTimeMillis() - start);
        }
    }

    private List<String> loadPlanIds(String personId) {
        String csv = planIndexByPerson.get(personId);
        if (csv == null || csv.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(COMMA_PATTERN.split(csv)));
    }

    @Override
    public void updateScore(String personId, String planId, double score, int iter) {
        String k = key(personId, planId);
        PlanData existing = getPlanData(personId, planId);
        if (existing != null) {
            PlanData updated = new PlanData(existing.blob, score, existing.creationIter, iter, existing.type);
            planDataMap.put(k, updated.serialize());
            creationIterCache.put(k, existing.creationIter);
        }
    }

    @Override
    public Plan materialize(String personId, String planId) {
        PlanData data = getPlanData(personId, planId);
        if (data == null) throw new IllegalStateException("Plan data missing for " + key(personId, planId));
        return codec.deserialize(data.blob);
    }

    @Override
    public boolean hasPlan(String personId, String planId) {
        synchronized (pendingWrites) {
            for (PendingWrite pw : pendingWrites) {
                if (pw.personId.equals(personId) && pw.planId.equals(planId)) {
                    return true;
                }
            }
        }
        return planDataMap.containsKey(key(personId, planId));
    }

    @Override
    public List<String> listPlanIds(String personId) {
        return planIdCache.computeIfAbsent(personId, this::loadPlanIds);
    }

    @Override
    public synchronized void deletePlan(String personId, String planId) {
        deletePlanInternal(personId, planId);
    }

    private void deletePlanInternal(String personId, String planId) {
        String k = key(personId, planId);
        planDataMap.remove(k);
        creationIterCache.remove(k);

        planIdCache.computeIfPresent(personId, (key, list) -> {
            List<String> newList = new ArrayList<>(list);
            newList.remove(planId);
            if (newList.isEmpty()) {
                planIndexByPerson.remove(personId);
                return null;
            }
            planIndexByPerson.put(personId, joinPlanIds(newList));
            return newList;
        });

        if (planId.equals(activePlanCache.get(personId))) {
            activePlanCache.remove(personId);
            activePlanByPerson.remove(personId);
        }
        
        removePlanProxyFromPerson(personId, planId);
    }
    
    private void removePlanProxyFromPerson(String personId, String planId) {
        org.matsim.api.core.v01.population.Person person = scenario.getPopulation().getPersons().get(
            org.matsim.api.core.v01.Id.createPersonId(personId)
        );
        if (person != null) {
            person.getPlans().removeIf(plan -> {
                if (plan instanceof PlanProxy proxy) {
                    return proxy.getPlanId().equals(planId);
                }
                return false;
            });
        }
    }

    @Override
    public void commit() {
        synchronized (pendingWrites) {
            flushPendingWrites();
        }
        enforceAllPlanLimits();
    }

    @Override
    public void close() {
        commit();
        db.close();
    }
}