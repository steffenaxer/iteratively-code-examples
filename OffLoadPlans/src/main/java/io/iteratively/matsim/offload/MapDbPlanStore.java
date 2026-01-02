package io.iteratively.matsim.offload;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mapdb.*;
import org.mapdb.serializer.SerializerCompressionWrapper;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Plan;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Hochperformante Plan-Speicherung mit MapDB und Fury Serialisierung.
 * 
 * PERFORMANCE-OPTIMIERUNGEN FÜR SCHREIB-OPERATIONEN:
 * 
 * 1. ELIMIERUNG VON DB-LOOKUPS WÄHREND FLUSH (größte Verbesserung!)
 *    - creationIterCache vermeidet tausende DB-Zugriffe beim Flush
 *    - Vorher: planDataMap.get() für jeden Plan während flush
 *    - Nachher: Direkte Cache-Nutzung
 * 
 * 2. GRÖSSERER WRITE-BUFFER
 *    - Erhöht von 50.000 auf 100.000 Einträge
 *    - Reduziert Anzahl der Flush-Operationen
 *    - Bessere Batch-Effizienz
 * 
 * 3. OPTIMIERTE DATEN-SERIALISIERUNG
 *    - serializeDirect() vermeidet PlanData-Objekt-Instanziierung
 *    - Vorallokierte ByteArrayOutputStream mit exakter Größe
 *    - Reduziert Memory-Allokationen
 * 
 * 4. MAPDB-KONFIGURATION
 *    - Transaktionen aktiviert für konsistente Batch-Commits
 *    - Moderate Startallokation (256 MB) und Inkrement (128 MB)
 *    - Memory-mapped I/O für schnelleren Zugriff
 * 
 * 5. THREAD-POOL FÜR ZUKÜNFTIGE PARALLELISIERUNG
 *    - Vorbereitet für parallele Serialisierung falls nötig
 *    - CPU-Kern-basierte Pool-Größe
 */
public final class MapDbPlanStore implements PlanStore {
    private static final Logger log = LogManager.getLogger(MapDbPlanStore.class);
    private static final Pattern COMMA_PATTERN = Pattern.compile(",");

    private final DB db;
    private final HTreeMap<String, byte[]> planDataMap;  // Konsolidiert: blob + metadata
    private final HTreeMap<String, String> activePlanByPerson;
    private final HTreeMap<String, String> planIndexByPerson;

    private final FuryPlanCodec codec;
    private final int maxPlansPerAgent;

    private final ConcurrentHashMap<String, List<String>> planIdCache;
    private final ConcurrentHashMap<String, String> activePlanCache;
    
    // Cache für creationIter um DB-Lookups während Flush zu vermeiden
    private final ConcurrentHashMap<String, Integer> creationIterCache;

    private final List<PendingWrite> pendingWrites = new ArrayList<>();
    private static final int WRITE_BUFFER_SIZE = 100_000;  // Noch größerer Buffer für weniger Flush-Operationen
    
    // Thread-Pool für parallele Serialisierung
    private final ExecutorService serializationExecutor;
    private final int parallelSerializationThreads;

    // Konsolidiertes Datenformat für alle Plan-Metadaten
    private record PlanData(byte[] blob, double score, int creationIter, int lastUsedIter, String type) implements Serializable {
        // Optimierte Serialisierung: direkt in vorbereiteten Buffer schreiben
        static byte[] serializeDirect(byte[] blob, double score, int creationIter, int lastUsedIter, String type) {
            try {
                // Berechne exakte Größe im Voraus
                int size = 4 + blob.length + 8 + 4 + 4 + 1 + (type != null ? 2 + type.getBytes("UTF-8").length : 0);
                ByteArrayOutputStream baos = new ByteArrayOutputStream(size);
                DataOutputStream dos = new DataOutputStream(baos);
                
                dos.writeInt(blob.length);
                dos.write(blob);
                dos.writeDouble(score);
                dos.writeInt(creationIter);
                dos.writeInt(lastUsedIter);
                dos.writeBoolean(type != null);
                if (type != null) dos.writeUTF(type);
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
        // Constructor mit automatischer Key-Generierung
        PendingWrite(String personId, String planId, byte[] blob, double score, int iter, boolean makeSelected, String type) {
            this(key(personId, planId), personId, planId, blob, score, iter, makeSelected, type);
        }
    }

    public MapDbPlanStore(File file, Scenario scenario, int maxPlansPerAgent) {
        this.maxPlansPerAgent = maxPlansPerAgent;
        this.planIdCache = new ConcurrentHashMap<>();
        this.activePlanCache = new ConcurrentHashMap<>();
        this.creationIterCache = new ConcurrentHashMap<>();
        
        // Thread-Pool für parallele Serialisierung (CPU-Kerne - 1, mindestens 2)
        this.parallelSerializationThreads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        this.serializationExecutor = Executors.newFixedThreadPool(
            parallelSerializationThreads,
            r -> {
                Thread t = new Thread(r, "PlanSerializer");
                t.setDaemon(true);
                return t;
            }
        );

        this.db = DBMaker
                .fileDB(file)
                .fileMmapEnableIfSupported()
                .allocateStartSize(256 * 1024 * 1024)  // 256 MB Startgröße (vernünftiger als 1 GB)
                .allocateIncrement(128 * 1024 * 1024)  // 128 MB Inkrement
                .fileMmapPreclearDisable()
                .transactionEnable()  // Transaktionen für batch commits
                .executorEnable()  // Async writes
                .closeOnJvmShutdown()
                .make();

        // Nur noch 3 Maps statt 7
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
    public List<PlanHeader> listPlanHeaders(String personId) {
        List<String> ids = listPlanIds(personId);
        if (ids.isEmpty()) return List.of();

        String activeId = activePlanCache.get(personId);
        if (activeId == null) activeId = activePlanByPerson.get(personId);

        List<PlanHeader> out = new ArrayList<>(ids.size());
        for (String pid : ids) {
            PlanData data = getPlanData(personId, pid);
            if (data != null) {
                boolean sel = pid.equals(activeId);
                out.add(new PlanHeader(pid, data.score, data.type, data.creationIter, data.lastUsedIter, sel));
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
        // Serialisierung AUSSERHALB des synchronized Blocks für bessere Performance
        byte[] blob = codec.serialize(plan);
        String planType = plan.getType();
        
        putPlanRaw(personId, planId, blob, score, iter, makeSelected, planType);
    }

    @Override
    public void putPlanRaw(String personId, String planId, byte[] blob, double score, int iter, boolean makeSelected) {
        putPlanRaw(personId, planId, blob, score, iter, makeSelected, null);
    }
    
    // Interne Methode mit planType Parameter
    private void putPlanRaw(String personId, String planId, byte[] blob, double score, int iter, boolean makeSelected, String planType) {
        String k = key(personId, planId);
        
        // Cache creationIter AUSSERHALB des synchronized Blocks
        creationIterCache.putIfAbsent(k, iter);
        
        boolean shouldFlush;
        synchronized (pendingWrites) {
            pendingWrites.add(new PendingWrite(personId, planId, blob, score, iter, makeSelected, planType));
            
            updatePlanIndexCache(personId, planId);
            if (makeSelected) {
                activePlanCache.put(personId, planId);
            }

            shouldFlush = pendingWrites.size() >= WRITE_BUFFER_SIZE;
        }
        
        // Flush AUSSERHALB des synchronized Blocks um Lock-Zeit zu minimieren
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

        // Gruppiere nach Key um nur den letzten Stand zu behalten
        Map<String, PendingWrite> latestByKey = new LinkedHashMap<>(size);
        for (PendingWrite pw : pendingWrites) {
            latestByKey.put(pw.key, pw);  // Nutze pre-computed key
            if (pw.makeSelected) {
                activeBatch.put(pw.personId, pw.planId);
            }
            affectedPersons.add(pw.personId);
        }

        // KRITISCHE OPTIMIERUNG: Nutze Cache statt DB-Lookups
        // Das eliminiert tausende langsame DB-Zugriffe während des Flush!
        for (Map.Entry<String, PendingWrite> entry : latestByKey.entrySet()) {
            PendingWrite pw = entry.getValue();
            
            // Nutze Cache für creationIter - kein DB-Lookup!
            int creationIter = creationIterCache.getOrDefault(pw.key, pw.iter);
            
            // Direkte Serialisierung ohne PlanData-Objekt zu erstellen
            byte[] serialized = PlanData.serializeDirect(pw.blob, pw.score, creationIter, pw.iter, pw.type);
            dataBatch.put(pw.key, serialized);
        }

        // Batch-Write: Alle Daten auf einmal schreiben
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

        // Commit Transaktion für konsistente Schreibvorgänge
        db.commit();

        pendingWrites.clear();

        log.info("Flush completed in {} ms (wrote {} plans)", System.currentTimeMillis() - start, latestByKey.size());
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
            // Update cache
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
        creationIterCache.remove(k);  // Cache aufräumen

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
        
        // Executor ordentlich beenden
        serializationExecutor.shutdown();
        try {
            if (!serializationExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                serializationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            serializationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        db.close();
    }
}