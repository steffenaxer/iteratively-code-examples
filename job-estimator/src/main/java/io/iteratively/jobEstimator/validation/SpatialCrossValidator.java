package io.iteratively.jobEstimator.validation;

import io.iteratively.jobEstimator.features.FeatureVector;
import io.iteratively.jobEstimator.grid.GridCell;
import io.iteratively.jobEstimator.model.SpatialModelTrainer;
import io.iteratively.jobEstimator.model.SpatialRegressionModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Envelope;

import java.util.*;

/**
 * Spatial block cross-validation that avoids autocorrelation inflation.
 *
 * <p>Random splits are inappropriate for spatial data because adjacent cells share OSM features —
 * random CV overestimates R² by 15–40%. This implementation partitions the study area into a
 * regular grid of super-blocks and performs leave-one-block-out evaluation.
 *
 * <p>Typical setup: 5×5 = 25 blocks over Switzerland (≈ 72×46 km each).
 * For final evaluation prefer canton-level folds (leave-one-canton-out).
 */
public final class SpatialCrossValidator {

    private static final Logger LOG = LogManager.getLogger(SpatialCrossValidator.class);

    /**
     * Assigns each cell to a spatial block in a {@code numBlocksPerDim × numBlocksPerDim} grid.
     *
     * @return zero-indexed block ID per cell; length equals {@code cells.size()}
     */
    public int[] createSpatialBlocks(List<GridCell> cells, int numBlocksPerDim) {
        Envelope bbox = computeBbox(cells);
        double blockW = bbox.getWidth()  / numBlocksPerDim;
        double blockH = bbox.getHeight() / numBlocksPerDim;

        int[] blockIds = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            GridCell c = cells.get(i);
            int col = (int) Math.min(numBlocksPerDim - 1,
                    (c.getCenterX() - bbox.getMinX()) / blockW);
            int row = (int) Math.min(numBlocksPerDim - 1,
                    (c.getCenterY() - bbox.getMinY()) / blockH);
            blockIds[i] = row * numBlocksPerDim + col;
        }
        return blockIds;
    }

    /**
     * Runs leave-one-block-out cross-validation.
     *
     * <p>Blocks with fewer than 2 labeled cells in train or test are silently skipped.
     *
     * @param cells    cells with both features and labels set
     * @param trainer  fully configured trainer (hyperparameters baked in)
     * @param numBlocks number of blocks per spatial dimension (total = numBlocks²)
     */
    public CrossValidationResult runBlockCV(List<GridCell> cells,
                                            SpatialModelTrainer trainer,
                                            int numBlocks) {
        int[] blockIds   = createSpatialBlocks(cells, numBlocks);
        int totalBlocks  = numBlocks * numBlocks;

        Map<Integer, List<Integer>> blockToIdx = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            blockToIdx.computeIfAbsent(blockIds[i], k -> new ArrayList<>()).add(i);
        }

        List<ValidationMetrics> foldMetrics = new ArrayList<>();

        for (int holdout = 0; holdout < totalBlocks; holdout++) {
            if (!blockToIdx.containsKey(holdout)) continue;

            List<GridCell> trainCells = new ArrayList<>();
            List<GridCell> testCells  = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) {
                (blockIds[i] == holdout ? testCells : trainCells).add(cells.get(i));
            }

            List<GridCell> usableTrain = trainCells.stream().filter(c -> c.hasLabel() && c.hasFeatures()).toList();
            List<GridCell> usableTest  = testCells.stream().filter(c -> c.hasLabel() && c.hasFeatures()).toList();

            if (usableTrain.size() < 2 || usableTest.isEmpty()) {
                LOG.debug("Block {} skipped (train={}, test={})", holdout, usableTrain.size(), usableTest.size());
                continue;
            }

            try (SpatialRegressionModel model = trainer.train(usableTrain)) {
                List<FeatureVector> testFeatures = usableTest.stream().map(GridCell::getFeatures).toList();
                float[] preds = model.predict(testFeatures);

                double[] actual    = usableTest.stream().mapToDouble(GridCell::getLabel).toArray();
                double[] predicted = new double[preds.length];
                for (int i = 0; i < preds.length; i++) predicted[i] = preds[i];

                ValidationMetrics metrics = ValidationMetrics.compute(actual, predicted);
                foldMetrics.add(metrics);
                LOG.info("Block CV fold {}/{}: {}", holdout + 1, totalBlocks, metrics);

            } catch (Exception e) {
                LOG.warn("Block CV fold {} failed: {}", holdout, e.getMessage());
            }
        }

        CrossValidationResult result = new CrossValidationResult(foldMetrics);
        LOG.info("Block CV complete ({} folds evaluated). Aggregate: {}", foldMetrics.size(), result.aggregateMean());
        return result;
    }

    private Envelope computeBbox(List<GridCell> cells) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE,
               maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (GridCell c : cells) {
            minX = Math.min(minX, c.getCenterX()); maxX = Math.max(maxX, c.getCenterX());
            minY = Math.min(minY, c.getCenterY()); maxY = Math.max(maxY, c.getCenterY());
        }
        return new Envelope(minX, maxX, minY, maxY);
    }
}
