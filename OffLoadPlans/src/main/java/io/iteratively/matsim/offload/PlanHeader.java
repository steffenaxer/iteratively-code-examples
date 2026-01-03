package io.iteratively.matsim.offload;

public final class PlanHeader {
    public final String planId;
    public double score;
    public final String type;
    public final String planMutator;
    public final int creationIter;
    public int lastUsedIter;
    public boolean selected;

    public PlanHeader(String planId, double score, String type, String planMutator, int creationIter, int lastUsedIter, boolean selected) {
        this.planId = planId;
        this.score = score;
        this.type = type;
        this.planMutator = planMutator;
        this.creationIter = creationIter;
        this.lastUsedIter = lastUsedIter;
        this.selected = selected;
    }
}
