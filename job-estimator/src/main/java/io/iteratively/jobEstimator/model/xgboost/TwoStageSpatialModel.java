package io.iteratively.jobEstimator.model.xgboost;

import io.iteratively.jobEstimator.features.FeatureVector;
import io.iteratively.jobEstimator.model.SpatialRegressionModel;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoostError;

import java.util.List;
import java.util.Map;

public final class TwoStageSpatialModel implements SpatialRegressionModel {

    private final Booster classifier;
    private final Booster regressor;
    private final float threshold;

    TwoStageSpatialModel(Booster classifier, Booster regressor, float threshold) {
        this.classifier = classifier;
        this.regressor = regressor;
        this.threshold = threshold;
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
            // Stage 1: classify
            float[][] classProbs = classifier.predict(dm);

            // Stage 2: regress (on all, then mask)
            float[][] regRaw = regressor.predict(dm);

            float[] result = new float[n];
            for (int i = 0; i < n; i++) {
                if (classProbs[i][0] >= threshold) {
                    result[i] = (float) Math.max(0.0, Math.expm1(regRaw[i][0]));
                } else {
                    result[i] = 0f;
                }
            }
            return result;
        } finally {
            dm.dispose();
        }
    }

    @Override
    public Map<String, Integer> getFeatureImportance() throws XGBoostError {
        return regressor.getFeatureScore((String[]) null);
    }

    Booster getClassifier() { return classifier; }
    Booster getRegressor() { return regressor; }

    @Override
    public void close() {
        classifier.dispose();
        regressor.dispose();
    }
}
