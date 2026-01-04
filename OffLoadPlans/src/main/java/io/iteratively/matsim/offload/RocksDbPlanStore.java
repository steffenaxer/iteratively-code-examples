package io.iteratively.matsim.offload;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.rocksdb.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

public final class RocksDbPlanStore implements PlanStore {
    private static final Logger log = LogManager.getLogger(RocksDbPlanStore.class);
    private static final Pattern COMMA_PATTERN = Pattern.compile(",");

    private final RocksDB db;
    private final WriteOptions writeOptions;
    private final ReadOptions readOptions;

    private final FuryPlanCodec codec;
    private final int maxPlansPerAgent;
    private final Scenario scenario;

    private final ConcurrentHashMap<String, List<String>> planIdCache;
    private final ConcurrentHashMap<String, String> activePlanCache;
    private final ConcurrentHashMap<String, Integer> creationIterCache;

    private final List<PendingWrite> pendingWrites = new ArrayList<>();
    private static final int WRITE_BUFFER_SIZE = 100_000;
    
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private static final byte[] PLAN_DATA_PREFIX = "pd:".getBytes();
    private static final byte[] ACTIVE_PLAN_PREFIX = "ap:".getBytes();
    private static final byte[] PLAN_INDEX_PREFIX = "pi:".getBytes();

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
            return new PendingWrite(RocksDbPlanStore.key(personId, planId), personId, planId, blob, score, iter, makeSelected, type);
        }
    }

    public RocksDbPlanStore(File directory, Scenario scenario, int maxPlansPerAgent) {
        this.maxPlansPerAgent = maxPlansPerAgent;
        this.scenario = scenario;
        this.planIdCache = new ConcurrentHashMap<>();
        this.activePlanCache = new ConcurrentHashMap<>();
        this.creationIterCache = new ConcurrentHashMap<>();

        try {
            RocksDB.loadLibrary();

            Options options = new Options();
            options.setCreateIfMissing(true);
            options.setWriteBufferSize(256 * 1024 * 1024);
            options.setMaxWriteBufferNumber(3);
            options.setCompressionType(CompressionType.LZ4_COMPRESSION);
            options.setMaxBackgroundJobs(4);

            this.db = RocksDB.open(options, directory.getAbsolutePath());

            this.writeOptions = new WriteOptions();
            this.writeOptions.setSync(false);
            this.writeOptions.setDisableWAL(false);

            this.readOptions = new ReadOptions();
            this.readOptions.setFillCache(true);

        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to initialize RocksDB", e);
        }

        this.codec = new FuryPlanCodec(scenario.getPopulation().getFactory());
    }

    private static String key(String personId, String planId) {
        return personId + "|" + planId;
    }

    private static byte[] prefixedKey(byte[] prefix, String key) {
        byte[] keyBytes = key.getBytes();
        byte[] result = new byte[prefix.length + keyBytes.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(keyBytes, 0, result, prefix.length, keyBytes.length);
        return result;
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
        
        try {
            byte[] value = db.get(readOptions, prefixedKey(ACTIVE_PLAN_PREFIX, personId));
            if (value != null) {
                String active = new String(value);
                activePlanCache.put(personId, active);
                return Optional.of(active);
            }
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to get active plan", e);
        }
        return Optional.empty();
    }

    @Override
    public void setActivePlanId(String personId, String planId) {
        activePlanCache.put(personId, planId);
        try {
            db.put(writeOptions, prefixedKey(ACTIVE_PLAN_PREFIX, personId), planId.getBytes());
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to set active plan", e);
        }
    }

    @Override
    public List<PlanProxy> listPlanProxies(Person person) {
        String personId = person.getId().toString();
        List<String> ids = listPlanIds(personId);
        if (ids.isEmpty()) return List.of();

        String activeId = activePlanCache.get(personId);
        if (activeId == null) {
            activeId = getActivePlanId(personId).orElse(null);
        }

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
        String k = key(personId, planId);
        
        lock.readLock().lock();
        try {
            for (int i = pendingWrites.size() - 1; i >= 0; i--) {
                PendingWrite pw = pendingWrites.get(i);
                if (pw.personId.equals(personId) && pw.planId.equals(planId)) {
                    int creationIter = creationIterCache.getOrDefault(k, pw.iter);
                    return new PlanData(pw.blob, pw.score, creationIter, pw.iter, pw.type);
                }
            }
            
            try {
                byte[] raw = db.get(readOptions, prefixedKey(PLAN_DATA_PREFIX, k));
                return raw != null ? PlanData.deserialize(raw) : null;
            } catch (RocksDBException e) {
                throw new RuntimeException("Failed to get plan data", e);
            }
        } finally {
            lock.readLock().unlock();
        }
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
        lock.readLock().lock();
        try {
            pendingWrites.add(PendingWrite.create(personId, planId, blob, score, iter, makeSelected, planType));
            updatePlanIndexCache(personId, planId);
            if (makeSelected) {
                activePlanCache.put(personId, planId);
            }
            shouldFlush = pendingWrites.size() >= WRITE_BUFFER_SIZE;
        } finally {
            lock.readLock().unlock();
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
        List<PendingWrite> toFlush;
        
        lock.writeLock().lock();
        try {
            if (pendingWrites.isEmpty()) return;
            toFlush = new ArrayList<>(pendingWrites);
            pendingWrites.clear();
        } finally {
            lock.writeLock().unlock();
        }

        log.info("Flushing {} pending writes to RocksDB...", toFlush.size());
        long start = System.currentTimeMillis();

        try (WriteBatch batch = new WriteBatch()) {
            Map<String, PendingWrite> latestByKey = new LinkedHashMap<>(toFlush.size());
            Set<String> affectedPersons = new HashSet<>();

            for (PendingWrite pw : toFlush) {
                latestByKey.put(pw.key, pw);
                if (pw.makeSelected) {
                    batch.put(prefixedKey(ACTIVE_PLAN_PREFIX, pw.personId), pw.planId.getBytes());
                }
                affectedPersons.add(pw.personId);
            }

            for (Map.Entry<String, PendingWrite> entry : latestByKey.entrySet()) {
                PendingWrite pw = entry.getValue();
                int creationIter = creationIterCache.getOrDefault(pw.key, pw.iter);
                byte[] serialized = PlanData.serializeDirect(pw.blob, pw.score, creationIter, pw.iter, pw.type);
                batch.put(prefixedKey(PLAN_DATA_PREFIX, pw.key), serialized);
            }

            for (String personId : affectedPersons) {
                List<String> currentIds = planIdCache.getOrDefault(personId, new ArrayList<>());
                batch.put(prefixedKey(PLAN_INDEX_PREFIX, personId), joinPlanIds(currentIds).getBytes());
            }

            db.write(writeOptions, batch);

            log.info("Flush completed in {} ms (wrote {} plans)", System.currentTimeMillis() - start, latestByKey.size());
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to flush pending writes", e);
        }
    }

    private int enforcePlanLimitLazy(String personId) {
        List<String> ids = planIdCache.get(personId);
        if (ids == null || ids.size() <= maxPlansPerAgent) return 0;

        String activeId = activePlanCache.get(personId);

        List<Map.Entry<String, Double>> scored = new ArrayList<>(ids.size());
        for (String pid : ids) {
            if (!pid.equals(activeId)) {
                PlanData data = getPlanData(personId, pid);
                double score = data != null ? data.score : Double.NEGATIVE_INFINITY;
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
        try {
            byte[] csv = db.get(readOptions, prefixedKey(PLAN_INDEX_PREFIX, personId));
            if (csv == null || csv.length == 0) return new ArrayList<>();
            String csvStr = new String(csv);
            return new ArrayList<>(Arrays.asList(COMMA_PATTERN.split(csvStr)));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to load plan IDs", e);
        }
    }

    @Override
    public void updateScore(String personId, String planId, double score, int iter) {
        String k = key(personId, planId);
        PlanData existing = getPlanData(personId, planId);
        if (existing != null) {
            PlanData updated = new PlanData(existing.blob, score, existing.creationIter, iter, existing.type);
            try {
                db.put(writeOptions, prefixedKey(PLAN_DATA_PREFIX, k), updated.serialize());
            } catch (RocksDBException e) {
                throw new RuntimeException("Failed to update score", e);
            }
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
        lock.readLock().lock();
        try {
            for (PendingWrite pw : pendingWrites) {
                if (pw.personId.equals(personId) && pw.planId.equals(planId)) {
                    return true;
                }
            }
            
            try {
                return db.get(readOptions, prefixedKey(PLAN_DATA_PREFIX, key(personId, planId))) != null;
            } catch (RocksDBException e) {
                throw new RuntimeException("Failed to check plan existence", e);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<String> listPlanIds(String personId) {
        return planIdCache.computeIfAbsent(personId, this::loadPlanIds);
    }

    @Override
    public void deletePlan(String personId, String planId) {
        lock.writeLock().lock();
        try {
            deletePlanInternal(personId, planId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void deletePlanInternal(String personId, String planId) {
        String k = key(personId, planId);
        
        try {
            db.delete(writeOptions, prefixedKey(PLAN_DATA_PREFIX, k));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to delete plan", e);
        }
        
        creationIterCache.remove(k);

        planIdCache.computeIfPresent(personId, (key, list) -> {
            List<String> newList = new ArrayList<>(list);
            newList.remove(planId);
            if (newList.isEmpty()) {
                try {
                    db.delete(writeOptions, prefixedKey(PLAN_INDEX_PREFIX, personId));
                } catch (RocksDBException e) {
                    throw new RuntimeException("Failed to delete plan index", e);
                }
                return null;
            }
            try {
                db.put(writeOptions, prefixedKey(PLAN_INDEX_PREFIX, personId), joinPlanIds(newList).getBytes());
            } catch (RocksDBException e) {
                throw new RuntimeException("Failed to update plan index", e);
            }
            return newList;
        });

        if (planId.equals(activePlanCache.get(personId))) {
            activePlanCache.remove(personId);
            try {
                db.delete(writeOptions, prefixedKey(ACTIVE_PLAN_PREFIX, personId));
            } catch (RocksDBException e) {
                throw new RuntimeException("Failed to delete active plan", e);
            }
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
        flushPendingWrites();
        enforceAllPlanLimits();
        
        try {
            db.flush(new FlushOptions().setWaitForFlush(true));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to commit", e);
        }
    }

    @Override
    public void close() {
        commit();
        
        try {
            if (db != null) {
                db.syncWal();
                db.cancelAllBackgroundWork(true);
            }
        } catch (RocksDBException e) {
            log.warn("Error during background work cancellation", e);
        }
        
        if (readOptions != null) readOptions.close();
        if (writeOptions != null) writeOptions.close();
        if (db != null) db.close();
        
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
