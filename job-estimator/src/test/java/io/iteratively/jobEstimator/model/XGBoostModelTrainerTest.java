package io.iteratively.jobEstimator.model;

import io.iteratively.jobEstimator.features.FeatureVector;
import io.iteratively.jobEstimator.grid.GridCell;
import io.iteratively.jobEstimator.model.xgboost.XGBoostModelTrainer;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class XGBoostModelTrainerTest {

    private static final int SEED = 42;

    private List<GridCell> makeSyntheticCells(int n) {
        Random rng = new Random(SEED);
        List<GridCell> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double cx = 2600000 + i * 250.0;
            double cy = 1200000.0;
            GridCell cell = new GridCell(i, cx, cy,
                    new Envelope(cx - 125, cx + 125, cy - 125, cy + 125));

            float[] v = new float[FeatureVector.FEATURE_COUNT];
            v[FeatureVector.F_BLDG_COMMERCIAL_COUNT] = rng.nextInt(10);
            v[FeatureVector.F_GHSL_BUILT_SURFACE]    = rng.nextFloat() * 10000;
            cell.setFeatures(new FeatureVector(v));
            cell.setLabel(v[FeatureVector.F_BLDG_COMMERCIAL_COUNT] * 20 + rng.nextDouble() * 50);
            cells.add(cell);
        }
        return cells;
    }

    @Test
    void testTrainProducesModel() throws Exception {
        List<GridCell> cells = makeSyntheticCells(100);
        Map<String, Object> params = XGBoostModelTrainer.defaultParams();
        params.put("nthread", 1);
        XGBoostModelTrainer trainer = new XGBoostModelTrainer(params, 10, 0, 0.0f);

        try (SpatialRegressionModel model = trainer.train(cells)) {
            assertNotNull(model);
        }
    }

    @Test
    void testPredictionsNonNegative() throws Exception {
        List<GridCell> cells = makeSyntheticCells(50);
        Map<String, Object> params = XGBoostModelTrainer.defaultParams();
        params.put("nthread", 1);
        XGBoostModelTrainer trainer = new XGBoostModelTrainer(params, 10, 0, 0.0f);

        try (SpatialRegressionModel model = trainer.train(cells)) {
            List<FeatureVector> features = cells.stream().map(GridCell::getFeatures).toList();
            float[] preds = model.predict(features);
            for (float p : preds) {
                assertTrue(p >= 0.0f, "Prediction must be non-negative, got: " + p);
            }
        }
    }

    @Test
    void testTrainSkipsCellsWithoutLabel() {
        List<GridCell> cells = makeSyntheticCells(50);
        for (int i = 0; i < 25; i++) {
            GridCell unlabeled = new GridCell(i + 1000, 0, 0, new Envelope(0, 250, 0, 250));
            unlabeled.setFeatures(cells.get(i).getFeatures());
            cells.set(i, unlabeled);
        }
        Map<String, Object> params = XGBoostModelTrainer.defaultParams();
        params.put("nthread", 1);
        XGBoostModelTrainer trainer = new XGBoostModelTrainer(params, 5, 0, 0.0f);

        assertDoesNotThrow(() -> {
            try (SpatialRegressionModel model = trainer.train(cells)) {
                assertNotNull(model);
            }
        });
    }

    @Test
    void testEmptyLabeledCellsThrows() {
        XGBoostModelTrainer trainer = new XGBoostModelTrainer(XGBoostModelTrainer.defaultParams(), 10, 0, 0.0f);
        assertThrows(IllegalArgumentException.class, () -> trainer.train(List.of()));
    }

    @Test
    void testDefaultParamsHasRequiredKeys() {
        Map<String, Object> params = XGBoostModelTrainer.defaultParams();
        assertTrue(params.containsKey("objective"));
        assertTrue(params.containsKey("eta"));
        assertTrue(params.containsKey("max_depth"));
    }
}
