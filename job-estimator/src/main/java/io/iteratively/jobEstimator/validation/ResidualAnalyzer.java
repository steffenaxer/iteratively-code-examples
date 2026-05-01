package io.iteratively.jobEstimator.validation;

import io.iteratively.jobEstimator.grid.GridCell;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Analyses spatial autocorrelation of model residuals via Global Moran's I.
 *
 * <p>A significant positive Moran's I (p &lt; 0.05) indicates that the model systematically
 * over- or under-predicts in spatial clusters — a sign of missing spatial features or
 * model misspecification.
 *
 * <p>The spatial weight matrix uses k-nearest-neighbour binary weights (1 if within k
 * nearest cells, 0 otherwise), which is appropriate for regular grids.
 */
public final class ResidualAnalyzer {

    private static final Logger LOG = LogManager.getLogger(ResidualAnalyzer.class);

    private static final int K_NEIGHBORS = 8;
    private static final int DEFAULT_PERMUTATIONS = 999;

    /** Computes per-cell residuals as {@code actual[i] - predicted[i]}. */
    public double[] computeResiduals(double[] actual, double[] predicted) {
        double[] residuals = new double[actual.length];
        for (int i = 0; i < actual.length; i++) residuals[i] = actual[i] - predicted[i];
        return residuals;
    }

    /**
     * Computes Global Moran's I for the given residuals using k-NN binary weights.
     *
     * @return value in [-1, 1]; positive = clustered, ~0 = random, negative = dispersed
     */
    public double globalMoransI(double[] residuals, List<GridCell> cells) {
        int n = cells.size();
        if (n < 4 || residuals.length != n) {
            throw new IllegalArgumentException(
                    "Need at least 4 cells and residuals.length == cells.size()");
        }

        double mean = Arrays.stream(residuals).average().orElse(0.0);
        double[] z = new double[n];
        for (int i = 0; i < n; i++) z[i] = residuals[i] - mean;

        int k = Math.min(K_NEIGHBORS, n - 1);
        int[][] neighbors = buildKnnNeighbors(cells, k);

        double numerator = 0.0, W = 0;
        for (int i = 0; i < n; i++) {
            for (int j : neighbors[i]) { numerator += z[i] * z[j]; W++; }
        }

        double denominator = 0.0;
        for (double zi : z) denominator += zi * zi;

        if (denominator == 0.0 || W == 0.0) return 0.0;
        return (n / W) * (numerator / denominator);
    }

    /**
     * Pseudo p-value for Moran's I via random permutation.
     *
     * @return fraction of permutations where |I_perm| &ge; |I_observed|;
     *         p &gt; 0.05 means residuals are not significantly spatially autocorrelated
     */
    public double moransIPValue(double[] residuals, List<GridCell> cells,
                                int numPermutations, long seed) {
        double observed = globalMoransI(residuals, cells);
        double[] shuffled = Arrays.copyOf(residuals, residuals.length);
        Random rng = new Random(seed);
        int extremeCount = 0;
        for (int p = 0; p < numPermutations; p++) {
            shuffleArray(shuffled, rng);
            if (Math.abs(globalMoransI(shuffled, cells)) >= Math.abs(observed)) extremeCount++;
        }
        double pValue = (double) extremeCount / numPermutations;
        LOG.info("Moran's I = {}, p-value = {} ({} permutations)",
                String.format(java.util.Locale.ROOT, "%.4f", observed),
                String.format(java.util.Locale.ROOT, "%.4f", pValue),
                numPermutations);
        return pValue;
    }

    /**
     * Serialises residuals to a GeoJSON FeatureCollection (WGS84 points) for QGIS QC.
     *
     * @param centerLon WGS84 longitude of each cell centre (same order as cells)
     * @param centerLat WGS84 latitude of each cell centre
     */
    public String residualsToGeoJson(double[] residuals, List<GridCell> cells,
                                     double[] centerLon, double[] centerLat) {
        StringBuilder sb = new StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[\n");
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(",\n");
            sb.append(String.format(java.util.Locale.ROOT,
                    "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[%.6f,%.6f]},"
                    + "\"properties\":{\"residual\":%.4f,\"cellId\":%d}}",
                    centerLon[i], centerLat[i], residuals[i], cells.get(i).getCellId()));
        }
        return sb.append("\n]}").toString();
    }

    private int[][] buildKnnNeighbors(List<GridCell> cells, int k) {
        int n = cells.size();
        double[] cx = cells.stream().mapToDouble(GridCell::getCenterX).toArray();
        double[] cy = cells.stream().mapToDouble(GridCell::getCenterY).toArray();
        int[][] neighbors = new int[n][];

        Integer[] indices = new Integer[n];
        for (int j = 0; j < n; j++) indices[j] = j;

        for (int i = 0; i < n; i++) {
            final int fi = i;
            double[] dist2 = new double[n];
            for (int j = 0; j < n; j++) {
                double dx = cx[fi] - cx[j], dy = cy[fi] - cy[j];
                dist2[j] = dx*dx + dy*dy;
            }
            Arrays.sort(indices, (a, b) -> Double.compare(dist2[a], dist2[b]));
            int cnt = 0;
            int[] tmp = new int[k];
            for (int j = 0; j < n && cnt < k; j++) {
                if (indices[j] != fi) tmp[cnt++] = indices[j];
            }
            neighbors[i] = cnt == k ? tmp : Arrays.copyOf(tmp, cnt);
        }
        return neighbors;
    }

    private void shuffleArray(double[] arr, Random rng) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            double tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
        }
    }
}
