package io.iteratively.jobEstimator.model;

import io.iteratively.jobEstimator.grid.GridCell;

import java.util.List;

/**
 * Backend-agnostic interface for training a spatial regression model.
 *
 * <p>Training hyperparameters and configuration are injected at construction time
 * of the concrete implementation, so the signature here stays generic and the
 * {@link io.iteratively.jobEstimator.pipeline.TrainingPipeline} is decoupled from
 * any particular ML library.
 *
 * <p>Example usage:
 * <pre>{@code
 * SpatialModelTrainer trainer = new XGBoostModelTrainer(XGBoostModelTrainer.defaultParams(), 300, 20, 0.1f);
 * SpatialRegressionModel model = trainer.train(labeledCells);
 * }</pre>
 */
public interface SpatialModelTrainer {

    /**
     * Trains a model on the supplied labeled cells.
     *
     * <p>Cells without features or labels are silently skipped.
     *
     * @param labeledCells cells with both {@code features} and {@code label} set
     * @return a trained, ready-to-use model (caller is responsible for closing it)
     * @throws IllegalArgumentException if no usable cells remain after filtering
     */
    SpatialRegressionModel train(List<GridCell> labeledCells) throws Exception;
}
