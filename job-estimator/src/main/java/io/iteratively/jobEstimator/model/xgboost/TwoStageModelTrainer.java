package io.iteratively.jobEstimator.model.xgboost;

import io.iteratively.jobEstimator.features.FeatureVector;
import io.iteratively.jobEstimator.grid.GridCell;
import io.iteratively.jobEstimator.model.SpatialModelTrainer;
import io.iteratively.jobEstimator.model.SpatialRegressionModel;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public final class TwoStageModelTrainer implements SpatialModelTrainer {

    private static final Logger LOG = LogManager.getLogger(TwoStageModelTrainer.class);

    private final Map<String, Object> classifierParams;
    private final Map<String, Object> regressorParams;
    private final int classifierRounds;
    private final int regressorRounds;
    private final int earlyStopRounds;

    public TwoStageModelTrainer(Map<String, Object> classifierParams,
                                Map<String, Object> regressorParams,
                                int classifierRounds,
                                int regressorRounds,
                                int earlyStopRounds) {
        this.classifierParams = Collections.unmodifiableMap(new LinkedHashMap<>(classifierParams));
        this.regressorParams = Collections.unmodifiableMap(new LinkedHashMap<>(regressorParams));
        this.classifierRounds = classifierRounds;
        this.regressorRounds = regressorRounds;
        this.earlyStopRounds = earlyStopRounds;
    }

    public static TwoStageModelTrainer withDefaults() {
        Map<String, Object> cParams = new LinkedHashMap<>();
        cParams.put("objective", "binary:logistic");
        cParams.put("eval_metric", "auc");
        cParams.put("max_depth", 5);
        cParams.put("eta", 0.05);
        cParams.put("subsample", 0.8);
        cParams.put("colsample_bytree", 0.8);
        cParams.put("min_child_weight", 10);
        cParams.put("scale_pos_weight", 1.0);
        cParams.put("nthread", Runtime.getRuntime().availableProcessors());
        cParams.put("seed", 42);

        Map<String, Object> rParams = XGBoostModelTrainer.defaultParams();

        return new TwoStageModelTrainer(cParams, rParams, 200, 500, 30);
    }

    public static TwoStageModelTrainer withParams(Map<String, Object> regressorParams, int regressorRounds) {
        Map<String, Object> cParams = new LinkedHashMap<>();
        cParams.put("objective", "binary:logistic");
        cParams.put("eval_metric", "auc");
        cParams.put("max_depth", 5);
        cParams.put("eta", 0.05);
        cParams.put("subsample", 0.8);
        cParams.put("colsample_bytree", 0.8);
        cParams.put("min_child_weight", 10);
        cParams.put("scale_pos_weight", 1.0);
        cParams.put("nthread", Runtime.getRuntime().availableProcessors());
        cParams.put("seed", 42);

        return new TwoStageModelTrainer(cParams, regressorParams, 200, regressorRounds, 30);
    }

    @Override
    public SpatialRegressionModel train(List<GridCell> labeledCells) throws XGBoostError {
        List<GridCell> usable = labeledCells.stream()
                .filter(c -> c.hasLabel() && c.hasFeatures())
                .toList();

        if (usable.isEmpty()) {
            throw new IllegalArgumentException("No cells with both features and labels");
        }

        // Stage 1: Binary classifier (employment > 0?)
        List<GridCell> positive = usable.stream().filter(c -> c.getLabel() > 0).toList();
        List<GridCell> negative = usable.stream().filter(c -> c.getLabel() == 0).toList();

        LOG.info("Two-stage training: {} total cells ({} positive, {} zero)",
                usable.size(), positive.size(), negative.size());

        // Compute scale_pos_weight for class imbalance
        double scalePosWeight = negative.isEmpty() ? 1.0 : (double) negative.size() / positive.size();
        Map<String, Object> adjClassParams = new LinkedHashMap<>(classifierParams);
        adjClassParams.put("scale_pos_weight", scalePosWeight);

        DMatrix classifierDm = toClassifierDMatrix(usable);
        Map<String, DMatrix> watches1 = new LinkedHashMap<>();
        watches1.put("train", classifierDm);

        var classifierBooster = XGBoost.train(classifierDm, adjClassParams, classifierRounds, watches1, null, null);
        classifierDm.dispose();
        LOG.info("Stage 1 (classifier) trained: {} rounds, scale_pos_weight={}",
                classifierRounds, String.format(java.util.Locale.ROOT, "%.2f", scalePosWeight));

        // Stage 2: Regression model trained only on positive cells
        if (positive.size() < 2) {
            LOG.warn("Too few positive cells ({}), falling back to single-stage", positive.size());
            classifierBooster.dispose();
            return new XGBoostModelTrainer(regressorParams, regressorRounds, earlyStopRounds, 0.1f)
                    .train(labeledCells);
        }

        DMatrix regressorDm = toRegressorDMatrix(positive);
        Map<String, DMatrix> watches2 = new LinkedHashMap<>();
        watches2.put("train", regressorDm);

        var regressorBooster = XGBoost.train(regressorDm, regressorParams, regressorRounds, watches2, null, null);
        regressorDm.dispose();
        LOG.info("Stage 2 (regressor) trained on {} positive cells: {} rounds",
                positive.size(), regressorRounds);

        return new TwoStageSpatialModel(classifierBooster, regressorBooster, 0.5f);
    }

    private DMatrix toClassifierDMatrix(List<GridCell> cells) throws XGBoostError {
        int n = cells.size();
        int f = FeatureVector.FEATURE_COUNT;
        float[] features = new float[n * f];
        float[] labels = new float[n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(cells.get(i).getFeatures().valuesUnsafe(), 0, features, i * f, f);
            labels[i] = cells.get(i).getLabel() > 0 ? 1f : 0f;
        }
        DMatrix dm = new DMatrix(features, n, f, Float.NaN);
        dm.setLabel(labels);
        return dm;
    }

    private DMatrix toRegressorDMatrix(List<GridCell> cells) throws XGBoostError {
        int n = cells.size();
        int f = FeatureVector.FEATURE_COUNT;
        float[] features = new float[n * f];
        float[] labels = new float[n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(cells.get(i).getFeatures().valuesUnsafe(), 0, features, i * f, f);
            labels[i] = (float) Math.log1p(cells.get(i).getLabel());
        }
        DMatrix dm = new DMatrix(features, n, f, Float.NaN);
        dm.setLabel(labels);
        return dm;
    }
}
