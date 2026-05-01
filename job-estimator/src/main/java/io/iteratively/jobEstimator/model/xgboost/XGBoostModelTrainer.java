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

/**
 * XGBoost implementation of {@link SpatialModelTrainer}.
 *
 * <p>Configuration is injected at construction; use {@link #withDefaults()} for a
 * reasonable starting point. Labels are {@code log1p}-transformed before training;
 * {@link XGBoostSpatialModel} reverses this at prediction time.
 *
 * <ul>
 *   <li>{@code max_depth=6} prevents overfitting to local spatial clusters</li>
 *   <li>{@code subsample=0.8} provides implicit spatial regularisation</li>
 *   <li>{@code min_child_weight=10} avoids overfitting on small clusters</li>
 * </ul>
 */
public final class XGBoostModelTrainer implements SpatialModelTrainer {

    private static final Logger LOG = LogManager.getLogger(XGBoostModelTrainer.class);

    private final Map<String, Object> params;
    private final int numRounds;
    private final int earlyStopRounds;
    private final float evalFraction;

    public XGBoostModelTrainer(Map<String, Object> params, int numRounds,
                               int earlyStopRounds, float evalFraction) {
        this.params         = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        this.numRounds      = numRounds;
        this.earlyStopRounds = earlyStopRounds;
        this.evalFraction   = evalFraction;
    }

    /** Factory returning a trainer with production-ready default hyperparameters. */
    public static XGBoostModelTrainer withDefaults() {
        return new XGBoostModelTrainer(defaultParams(), 300, 20, 0.1f);
    }

    public static Map<String, Object> defaultParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("objective",        "reg:squarederror");
        p.put("eval_metric",      "rmse");
        p.put("max_depth",        6);
        p.put("eta",              0.05);
        p.put("subsample",        0.8);
        p.put("colsample_bytree", 0.8);
        p.put("min_child_weight", 10);
        p.put("alpha",            0.1);
        p.put("lambda",           1.0);
        p.put("nthread",          Runtime.getRuntime().availableProcessors());
        p.put("seed",             42);
        return p;
    }

    @Override
    public SpatialRegressionModel train(List<GridCell> labeledCells) throws XGBoostError {
        List<GridCell> usable = labeledCells.stream()
                .filter(c -> c.hasLabel() && c.hasFeatures())
                .toList();

        if (usable.isEmpty()) {
            throw new IllegalArgumentException("No cells with both features and labels available for training");
        }

        LOG.info("Training XGBoost on {} cells (rounds={}, earlyStop={})",
                usable.size(), numRounds, earlyStopRounds);

        int splitIdx = (evalFraction > 0 && earlyStopRounds > 0)
                ? (int) (usable.size() * (1.0 - evalFraction))
                : usable.size();
        List<GridCell> trainCells = usable.subList(0, splitIdx);
        List<GridCell> evalCells  = splitIdx < usable.size() ? usable.subList(splitIdx, usable.size()) : List.of();

        DMatrix trainDm = toDMatrix(trainCells);
        Map<String, DMatrix> watches = new LinkedHashMap<>();
        watches.put("train", trainDm);

        DMatrix evalDm = null;
        if (!evalCells.isEmpty()) {
            evalDm = toDMatrix(evalCells);
            watches.put("eval", evalDm);
        }

        var booster = XGBoost.train(trainDm, params, numRounds, watches, null, null);

        trainDm.dispose();
        if (evalDm != null) evalDm.dispose();

        LOG.info("XGBoost training complete");
        return new XGBoostSpatialModel(booster);
    }

    DMatrix toDMatrix(List<GridCell> cells) throws XGBoostError {
        int n = cells.size();
        int f = FeatureVector.FEATURE_COUNT;
        float[] features = new float[n * f];
        float[] labels   = new float[n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(cells.get(i).getFeatures().valuesUnsafe(), 0, features, i * f, f);
            labels[i] = (float) Math.log1p(cells.get(i).getLabel());
        }
        DMatrix dm = new DMatrix(features, n, f, Float.NaN);
        dm.setLabel(labels);
        return dm;
    }
}
