package io.iteratively.jobEstimator.model.xgboost;

import io.iteratively.jobEstimator.model.SpatialModelSerializer;
import io.iteratively.jobEstimator.model.SpatialRegressionModel;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Saves and loads {@link XGBoostSpatialModel}s using the XGBoost Universal Binary JSON
 * ({@code .ubj}) format — portable across platforms since XGBoost 1.6.
 *
 * <p>A JSON metadata sidecar (training date, num_rounds, metrics) can optionally be
 * written alongside the model file via {@link #saveMetadata}.
 */
public final class XGBoostModelSerializer implements SpatialModelSerializer {

    private static final Logger LOG = LogManager.getLogger(XGBoostModelSerializer.class);

    @Override
    public void save(SpatialRegressionModel model, Path outputPath) throws XGBoostError, IOException {
        Files.createDirectories(outputPath.getParent());

        if (model instanceof TwoStageSpatialModel twoStage) {
            String base = outputPath.toString().replaceFirst("\\.[^.]+$", "");
            twoStage.getClassifier().saveModel(base + "_classifier.ubj");
            twoStage.getRegressor().saveModel(base + "_regressor.ubj");
            // Write a marker so load() knows it's a two-stage model
            Path marker = outputPath.resolveSibling(outputPath.getFileName().toString()
                    .replaceFirst("\\.[^.]+$", "") + "_twostage.json");
            Files.writeString(marker, "{\"type\":\"twostage\",\"threshold\":0.5}", StandardCharsets.UTF_8);
            LOG.info("Two-stage model saved to {}_classifier.ubj + {}_regressor.ubj", base, base);
        } else if (model instanceof XGBoostSpatialModel xgbModel) {
            xgbModel.getBooster().saveModel(outputPath.toString());
            LOG.info("XGBoost model saved to {}", outputPath);
        } else {
            throw new IllegalArgumentException("Unsupported model type: " + model.getClass().getSimpleName());
        }
    }

    @Override
    public SpatialRegressionModel load(Path modelPath) throws XGBoostError, IOException {
        String base = modelPath.toString().replaceFirst("\\.[^.]+$", "");
        Path marker = modelPath.resolveSibling(modelPath.getFileName().toString()
                .replaceFirst("\\.[^.]+$", "") + "_twostage.json");

        if (Files.exists(marker)) {
            Booster classifier = XGBoost.loadModel(base + "_classifier.ubj");
            Booster regressor = XGBoost.loadModel(base + "_regressor.ubj");
            LOG.info("Two-stage model loaded from {}", base);
            return new TwoStageSpatialModel(classifier, regressor, 0.5f);
        }

        Booster booster = XGBoost.loadModel(modelPath.toString());
        LOG.info("XGBoost model loaded from {}", modelPath);
        return new XGBoostSpatialModel(booster);
    }

    /** Writes a JSON sidecar with arbitrary training metadata next to the model file. */
    public void saveMetadata(Path modelPath, Map<String, Object> metadata) throws IOException {
        String baseName = modelPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Path sidecar = modelPath.resolveSibling(baseName + "_metadata.json");
        Files.writeString(sidecar, new JSONObject(metadata).toString(2), StandardCharsets.UTF_8);
        LOG.info("Metadata saved to {}", sidecar);
    }

    /** Reads the JSON sidecar written by {@link #saveMetadata}. */
    public JSONObject loadMetadata(Path modelPath) throws IOException {
        String baseName = modelPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Path sidecar = modelPath.resolveSibling(baseName + "_metadata.json");
        return new JSONObject(Files.readString(sidecar, StandardCharsets.UTF_8));
    }
}
