package io.iteratively.matsim.offload;

import org.mapdb.*;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Plan;

import java.io.File;
import java.util.*;
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

    public MapDbPlanStore(File file, Scenario scenario, int maxPlansPerAgent) {
        this.maxPlansPerAgent = maxPlansPerAgent;
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

    private static String key(String personId, String planId) { return personId + "|" + planId; }

    @Override public Optional<String> getActivePlanId(String personId) { return Optional.ofNullable(activePlanByPerson.get(personId)); }
    @Override public void setActivePlanId(String personId, String planId) { activePlanByPerson.put(personId, planId); }

    @Override public List<PlanHeader> listPlanHeaders(String personId) {
        String csv = planIndexByPerson.get(personId);
        if (csv == null || csv.isEmpty()) return List.of();
        String[] ids = csv.split(",");
        List<PlanHeader> out = new ArrayList<>(ids.length);
        for (String pid : ids) {
            String k = key(personId, pid);
            double score = planScores.getOrDefault(k, Double.NEGATIVE_INFINITY);
            String type = planTypes.get(k);
            int cIter = planCreationIter.getOrDefault(k, -1);
            int lIter = planLastUsedIter.getOrDefault(k, -1);
            boolean sel = pid.equals(activePlanByPerson.get(personId));
            out.add(new PlanHeader(pid, score, type, cIter, lIter, sel));
        }
        return out;
    }

    @Override public void putPlan(String personId, String planId, Plan plan, double score, int iter, boolean makeSelected) {
        byte[] blob = codec.serialize(plan);
        String k = key(personId, planId);
        planBlobs.put(k, blob);
        planScores.put(k, score);
        if (plan.getType() != null) planTypes.put(k, plan.getType());
        planCreationIter.putIfAbsent(k, iter);
        planLastUsedIter.put(k, iter);
        var list = new ArrayList<>(listPlanIds(personId));
        if (!list.contains(planId)) {
            list.add(planId);
            planIndexByPerson.put(personId, String.join(",", list));
        }
        if (makeSelected) { activePlanByPerson.put(personId, planId); }

        enforcePlanLimit(personId);
    }

    private void enforcePlanLimit(String personId) {
        List<PlanHeader> headers = listPlanHeaders(personId);
        if (headers.size() <= maxPlansPerAgent) return;

        String activeId = activePlanByPerson.get(personId);
        headers.stream()
                .filter(h -> !h.planId.equals(activeId))
                .sorted(Comparator.comparingDouble(h -> h.score))
                .limit(headers.size() - maxPlansPerAgent)
                .forEach(h -> deletePlan(personId, h.planId));
    }

    @Override public void updateScore(String personId, String planId, double score, int iter) {
        String k = key(personId, planId);
        planScores.put(k, score);
        planLastUsedIter.put(k, iter);
    }

    @Override public Plan materialize(String personId, String planId) {
        String k = key(personId, planId);
        byte[] blob = planBlobs.get(k);
        if (blob == null) throw new IllegalStateException("Plan blob missing for " + k);
        return codec.deserialize(blob);
    }

    @Override public boolean hasPlan(String personId, String planId) { return planBlobs.containsKey(key(personId, planId)); }

    @Override public List<String> listPlanIds(String personId) {
        String csv = planIndexByPerson.get(personId);
        if (csv == null || csv.isEmpty()) return List.of();
        return Arrays.stream(csv.split(",")).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    @Override public void deletePlan(String personId, String planId) {
        String k = key(personId, planId);
        planBlobs.remove(k);
        planScores.remove(k);
        planCreationIter.remove(k);
        planLastUsedIter.remove(k);
        planTypes.remove(k);
        var list = new ArrayList<>(listPlanIds(personId));
        list.remove(planId);
        if (list.isEmpty()) planIndexByPerson.remove(personId);
        else planIndexByPerson.put(personId, String.join(",", list));
        if (planId.equals(activePlanByPerson.get(personId))) activePlanByPerson.remove(personId);
    }

    @Override public void commit() { db.commit(); }

    @Override public void close() { db.commit(); db.close(); }
}
