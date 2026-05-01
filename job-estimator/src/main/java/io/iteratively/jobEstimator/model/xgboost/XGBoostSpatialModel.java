package io.iteratively.jobEstimator.model.xgboost;

import io.iteratively.jobEstimator.features.FeatureVector;
import io.iteratively.jobEstimator.model.SpatialRegressionModel;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoostError;

import java.util.List;
import java.util.Map;

/**
 * XGBoost-backed implementation of {@link SpatialRegressionModel}.
 *
 * <p>Labels were trained on {@code log1p}-transformed employment counts, so
 * predictions are automatically back-transformed via {@code expm1} and clamped to [0, ∞).
 */
public final class XGBoostSpatialModel implements SpatialRegressionModel {

    private final Booster booster;

    XGBoostSpatialModel(Booster booster) {
        this.booster = booster;
    }

    @Override
    public float[] predict(List<FeatureVector> features) throws XGBoostError {
        int n = features.size();
        float[] flat = new float[n * FeatureVector.FEATURE_COUNT];
        for (int i = 0; i < n; i++) {
            System.arraycopy(features.get(i).valuesUnsafe(), 0, flat, i * FeatureVector.FEATURE_COUNT, FeatureVector.FEATURE_COUNT);
        }
        DMatrix dm = new DMatrix(flat, n, FeatureVector.FEATURE_COUNT, Float.NaN);
        try {
            float[][] raw = booster.predict(dm);
            float[] result = new float[n];
            for (int i = 0; i < n; i++) {
                result[i] = (float) Math.max(0.0, Math.expm1(raw[i][0]));
            }
            return result;
        } finally {
            dm.dispose();
        }
    }

    @Override
    public Map<String, Integer> getFeatureImportance() throws XGBoostError {
        return booster.getFeatureScore((String[]) null);
    }

    /** Package-private access for {@link XGBoostModelSerializer}. */
    Booster getBooster() {
        return booster;
    }

    @Override
    public void close() {
        booster.dispose();
    }
}
