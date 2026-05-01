package io.iteratively.jobEstimator.grid;

import io.iteratively.jobEstimator.features.FeatureVector;
import org.locationtech.jts.geom.Envelope;

/**
 * A single grid cell in the regular 250 m (or configurable) grid.
 *
 * <p>Coordinates are in the native projected CRS of the study area
 * (LV95 / EPSG:2056 for Switzerland, UTM32N / EPSG:25832 for Braunschweig).
 *
 * <p>A cell may have:
 * <ul>
 *   <li>features — attached after {@link io.iteratively.jobEstimator.features.FeatureExtractor} runs</li>
 *   <li>label — employment count aggregated from STATENT (training only)</li>
 * </ul>
 */
public final class GridCell {

    private final long cellId;
    private final double centerX;
    private final double centerY;
    private final Envelope bounds;

    private FeatureVector features;
    private Double label;

    public GridCell(long cellId, double centerX, double centerY, Envelope bounds) {
        this.cellId  = cellId;
        this.centerX = centerX;
        this.centerY = centerY;
        this.bounds  = bounds;
    }

    public long getCellId() { return cellId; }
    public double getCenterX() { return centerX; }
    public double getCenterY() { return centerY; }
    public Envelope getBounds() { return bounds; }

    public FeatureVector getFeatures() { return features; }
    public void setFeatures(FeatureVector features) { this.features = features; }
    public boolean hasFeatures() { return features != null; }

    public boolean hasLabel() { return label != null; }

    public double getLabel() {
        if (label == null) throw new IllegalStateException("Cell " + cellId + " has no label");
        return label;
    }

    public void setLabel(double label) { this.label = label; }

    /** Returns true if the cell has features and at least one non-zero value. */
    public boolean hasData() {
        if (features == null) return false;
        float[] v = features.values();
        for (float f : v) {
            if (f != 0f) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "GridCell{id=" + cellId + ", cx=" + centerX + ", cy=" + centerY +
               ", label=" + (label != null ? label : "none") + "}";
    }
}
