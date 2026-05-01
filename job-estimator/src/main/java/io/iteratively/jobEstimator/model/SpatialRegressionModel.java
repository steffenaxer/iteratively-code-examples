package io.iteratively.jobEstimator.model;

import io.iteratively.jobEstimator.features.FeatureVector;

import java.util.List;
import java.util.Map;

/**
 * Backend-agnostic interface for a trained spatial job-count regression model.
 *
 * <p>Concrete implementations wrap a specific ML library (XGBoost, RandomForest, …).
 * The {@link io.iteratively.jobEstimator.pipeline.TrainingPipeline} and
 * {@link io.iteratively.jobEstimator.pipeline.InferencePipeline} depend only on this interface.
 *
 * <p>Implementations must be closed after use to release native resources.
 */
public interface SpatialRegressionModel extends AutoCloseable {

    /**
     * Predicts absolute job counts for a batch of feature vectors.
     * Results are already back-transformed from any internal label encoding (e.g. log1p).
     *
     * @return non-negative job count estimates, one per input vector
     */
    float[] predict(List<FeatureVector> features) throws Exception;

    /** Convenience single-cell prediction. */
    default float predictSingle(FeatureVector fv) throws Exception {
        return predict(List.of(fv))[0];
    }

    /**
     * Returns feature importance scores keyed by feature name.
     * Semantics depend on the backend (gain for XGBoost, impurity decrease for RF, …).
     * May return an empty map if the backend does not support feature importance.
     */
    Map<String, Integer> getFeatureImportance() throws Exception;

    @Override
    void close();
}
