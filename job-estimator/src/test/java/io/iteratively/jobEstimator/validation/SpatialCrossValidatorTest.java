package io.iteratively.jobEstimator.validation;

import io.iteratively.jobEstimator.features.FeatureVector;
import io.iteratively.jobEstimator.grid.GridCell;
import io.iteratively.jobEstimator.model.SpatialModelTrainer;
import io.iteratively.jobEstimator.model.xgboost.XGBoostModelTrainer;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SpatialCrossValidatorTest {

    private List<GridCell> makeSyntheticGrid(int nCols, int nRows) {
        Random rng = new Random(0);
        List<GridCell> cells = new ArrayList<>();
        long id = 0;
        for (int row = 0; row < nRows; row++) {
            for (int col = 0; col < nCols; col++) {
                double cx = 2600000 + col * 250.0;
                double cy = 1200000 + row * 250.0;
                GridCell cell = new GridCell(id++, cx, cy,
                        new Envelope(cx - 125, cx + 125, cy - 125, cy + 125));

                float[] v = new float[FeatureVector.FEATURE_COUNT];
                v[FeatureVector.F_BLDG_COMMERCIAL_COUNT] = rng.nextInt(5);
                v[FeatureVector.F_GHSL_BUILT_SURFACE]    = rng.nextFloat() * 5000;
                cell.setFeatures(new FeatureVector(v));
                cell.setLabel(v[FeatureVector.F_BLDG_COMMERCIAL_COUNT] * 15 + rng.nextDouble() * 30);
                cells.add(cell);
            }
        }
        return cells;
    }

    @Test
    void spatialBlockAssignmentCoverAllCells() {
        List<GridCell> cells = makeSyntheticGrid(10, 10);
        SpatialCrossValidator cv = new SpatialCrossValidator();
        int[] blockIds = cv.createSpatialBlocks(cells, 3);
        assertEquals(cells.size(), blockIds.length);
        for (int id : blockIds) {
            assertTrue(id >= 0 && id < 9, "Block ID out of range: " + id);
        }
    }

    @Test
    void blockCvReturnsSomeFolds() {
        List<GridCell> cells = makeSyntheticGrid(12, 12);
        Map<String, Object> params = XGBoostModelTrainer.defaultParams();
        params.put("nthread", 1);
        SpatialModelTrainer trainer = new XGBoostModelTrainer(params, 5, 0, 0.0f);

        SpatialCrossValidator cv = new SpatialCrossValidator();
        CrossValidationResult result = cv.runBlockCV(cells, trainer, 3);

        assertFalse(result.foldMetrics().isEmpty(), "Expected at least one evaluated fold");
        result.foldMetrics().forEach(m -> assertTrue(Double.isFinite(m.r2())));
    }

    @Test
    void aggregateMeanFiniteForNonEmptyResult() {
        List<GridCell> cells = makeSyntheticGrid(10, 10);
        Map<String, Object> params = XGBoostModelTrainer.defaultParams();
        params.put("nthread", 1);
        SpatialModelTrainer trainer = new XGBoostModelTrainer(params, 5, 0, 0.0f);

        CrossValidationResult result = new SpatialCrossValidator().runBlockCV(cells, trainer, 2);
        ValidationMetrics agg = result.aggregateMean();
        assertTrue(Double.isFinite(agg.mae()));
        assertTrue(Double.isFinite(agg.rmse()));
    }
}
