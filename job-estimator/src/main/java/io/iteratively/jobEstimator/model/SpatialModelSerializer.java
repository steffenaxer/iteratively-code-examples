package io.iteratively.jobEstimator.model;

import java.nio.file.Path;

/**
 * Backend-agnostic interface for persisting and restoring a {@link SpatialRegressionModel}.
 *
 * <p>Each concrete {@link SpatialModelTrainer} implementation ships a matching serializer.
 * The format (XGBoost {@code .ubj}, PMML, ONNX, …) is an implementation detail.
 */
public interface SpatialModelSerializer {

    /**
     * Serialises {@code model} to {@code outputPath}.
     * Parent directories are created if they do not exist.
     */
    void save(SpatialRegressionModel model, Path outputPath) throws Exception;

    /**
     * Deserialises a model from {@code modelPath}.
     * The caller is responsible for closing the returned model.
     */
    SpatialRegressionModel load(Path modelPath) throws Exception;
}
