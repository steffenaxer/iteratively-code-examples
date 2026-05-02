package io.iteratively.jobEstimator;

import io.iteratively.jobEstimator.grid.GeoJsonRegionReader;
import io.iteratively.jobEstimator.model.SpatialModelTrainer;
import io.iteratively.jobEstimator.model.xgboost.*;
import io.iteratively.jobEstimator.pipeline.InferencePipeline;
import io.iteratively.jobEstimator.pipeline.TrainingPipeline;
import io.iteratively.jobEstimator.pipeline.TuningTrainingPipeline;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Envelope;

import java.nio.file.Path;

/**
 * Entry point for the job-estimator pipeline.
 *
 * <p>Run via Maven (from project root):
 * <pre>
 *   # Full Switzerland training (default, single-stage XGBoost)
 *   mvn exec:java -pl job-estimator
 *
 *   # Two-stage model (binary classifier + regressor)
 *   mvn exec:java -pl job-estimator -Dmodel=twostage
 *
 *   # Hyperparameter tuning then train with best params
 *   mvn exec:java -pl job-estimator -Dmode=tune
 *
 *   # Kanton Zug only (fast, ~2 min)
 *   mvn exec:java -pl job-estimator -Dmode=train -Dbbox=2665000,1209000,2705000,1244000
 *
 *   # Inference on saved model
 *   mvn exec:java -pl job-estimator -Dmode=predict
 *
 *   # With all GHSL raster layers (download via job-estimator/download_ghsl.sh)
 *   mvn exec:java -pl job-estimator \
 *     -DghslBuilt=data/ghsl/GHS_BUILT_S_E2020_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif \
 *     -DghslHeight=data/ghsl/GHS_BUILT_H_AGBH_E2018_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif \
 *     -DghslPop=data/ghsl/GHS_POP_E2020_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif
 * </pre>
 */
public final class JobEstimatorMain {

    private static final Logger LOG = LogManager.getLogger(JobEstimatorMain.class);

    public static void main(String[] args) throws Exception {
        // ── Paths ──────────────────────────────────────────────────────────────
        Path statentCsv = Path.of(prop("statent",
                "data/ag-b-00.03-22-STATENT2023/STATENT_2023.csv"));
        Path osmPbf     = Path.of(prop("osm",
                "data/osm/switzerland-latest.osm.pbf"));
        Path outputDir  = Path.of(prop("output", "output/job-estimator"));

        String ghslBuiltStr  = prop("ghslBuilt", null);
        String ghslPopStr    = prop("ghslPop", null);
        String ghslHeightStr = prop("ghslHeight", null);
        Path ghslBuiltPath   = ghslBuiltStr  != null ? Path.of(ghslBuiltStr)  : null;
        Path ghslPopPath     = ghslPopStr    != null ? Path.of(ghslPopStr)    : null;
        Path ghslHeightPath  = ghslHeightStr != null ? Path.of(ghslHeightStr) : null;

        // ── Tuning knobs ───────────────────────────────────────────────────────
        String mode      = prop("mode", "train");
        String modelType = prop("model", "single");  // "single" or "twostage"
        int    rounds    = Integer.parseInt(prop("rounds", "300"));
        int    cvBlocks  = Integer.parseInt(prop("cvBlocks", "5"));
        int    cellSize  = Integer.parseInt(prop("cellSize", "250"));
        String crsCode   = prop("crs", "EPSG:2056");
        String bboxStr   = prop("bbox", null);
        String regionStr = prop("region", null);

        LOG.info("JobEstimatorMain  mode={} model={} rounds={} cvBlocks={}×{}", mode, modelType, rounds, cvBlocks, cvBlocks);

        XGBoostModelSerializer serializer = new XGBoostModelSerializer();

        // ── Tune ───────────────────────────────────────────────────────────────
        if ("tune".equals(mode) || "quicktune".equals(mode)) {
            var searchMode = "quicktune".equals(mode)
                    ? io.iteratively.jobEstimator.model.xgboost.HyperparameterSearch.SearchMode.QUICK
                    : io.iteratively.jobEstimator.model.xgboost.HyperparameterSearch.SearchMode.FULL;
            LOG.info("Running hyperparameter tuning (mode={})...", searchMode);
            TrainingPipeline.Config config = buildTrainConfig(statentCsv, osmPbf, outputDir,
                    ghslBuiltPath, ghslPopPath, ghslHeightPath, crsCode, cellSize, cvBlocks, bboxStr);
            new TuningTrainingPipeline(config, serializer, modelType, cvBlocks, searchMode).run();
            return;
        }

        // ── Train ──────────────────────────────────────────────────────────────
        if ("train".equals(mode) || "all".equals(mode)) {
            TrainingPipeline.Config config = buildTrainConfig(statentCsv, osmPbf, outputDir,
                    ghslBuiltPath, ghslPopPath, ghslHeightPath, crsCode, cellSize, cvBlocks, bboxStr);

            SpatialModelTrainer trainer = buildTrainer(modelType, rounds);
            new TrainingPipeline(config, trainer, serializer).run();
        }

        // ── Predict ────────────────────────────────────────────────────────────
        if ("predict".equals(mode) || "all".equals(mode)) {
            Path modelPath = outputDir.resolve("model.ubj");
            Path twoStageMarker = outputDir.resolve("model_twostage.json");
            if (!modelPath.toFile().exists() && !twoStageMarker.toFile().exists()) {
                throw new IllegalStateException("Model not found at " + modelPath + ". Run with -Dmode=train first.");
            }

            InferencePipeline.Config config;
            if (regionStr != null) {
                config = inferenceConfigFromRegion(modelPath, osmPbf, outputDir,
                        ghslBuiltPath, ghslPopPath, ghslHeightPath, crsCode, cellSize, regionStr);
            } else if (bboxStr != null) {
                config = inferenceConfig(modelPath, osmPbf, outputDir,
                        ghslBuiltPath, ghslPopPath, ghslHeightPath, crsCode, cellSize, bboxStr);
            } else {
                config = InferencePipeline.Config.switzerland(modelPath, osmPbf, outputDir);
            }

            new InferencePipeline(config, serializer).run();
        }
    }

    private static SpatialModelTrainer buildTrainer(String modelType, int rounds) {
        if ("twostage".equals(modelType)) {
            LOG.info("Using two-stage model (classifier + regressor)");
            return TwoStageModelTrainer.withDefaults();
        }
        return new XGBoostModelTrainer(XGBoostModelTrainer.defaultParams(), rounds, 20, 0.1f);
    }

    private static TrainingPipeline.Config buildTrainConfig(
            Path statent, Path osm, Path out,
            Path ghslBuilt, Path ghslPop, Path ghslHeight,
            String crs, int cellSize, int cvBlocks, String bboxStr) {
        if (bboxStr != null) {
            double[] b = parseBbox(bboxStr);
            return new TrainingPipeline.Config(statent, osm, ghslBuilt, ghslPop, ghslHeight, null,
                    crs, cellSize, cvBlocks, out, b[0], b[1], b[2], b[3]);
        }
        return new TrainingPipeline.Config(statent, osm, ghslBuilt, ghslPop, ghslHeight, null,
                "EPSG:2056", 250, cvBlocks, out, null, null, null, null);
    }

    private static InferencePipeline.Config inferenceConfig(
            Path model, Path osm, Path out,
            Path ghslBuilt, Path ghslPop, Path ghslHeight,
            String crs, int cellSize, String bboxStr) {
        double[] b = parseBbox(bboxStr);
        return new InferencePipeline.Config(model, osm, ghslBuilt, ghslPop, ghslHeight, null,
                crs, b[0], b[1], b[2], b[3], cellSize, out);
    }

    private static InferencePipeline.Config inferenceConfigFromRegion(
            Path model, Path osm, Path out,
            Path ghslBuilt, Path ghslPop, Path ghslHeight,
            String crs, int cellSize, String regionPath) throws Exception {
        GeoJsonRegionReader reader = new GeoJsonRegionReader();
        GeoJsonRegionReader.RegionResult region = reader.read(Path.of(regionPath), crs);
        Envelope env = region.envelope();
        return new InferencePipeline.Config(model, osm, ghslBuilt, ghslPop, ghslHeight, null,
                crs, env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY(),
                cellSize, out, region.geometry());
    }

    private static double[] parseBbox(String s) {
        String[] p = s.split(",");
        if (p.length != 4)
            throw new IllegalArgumentException("bbox must be minX,minY,maxX,maxY — got: " + s);
        double[] b = {
                Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                Double.parseDouble(p[2]), Double.parseDouble(p[3])
        };
        if (b[0] >= b[2] || b[1] >= b[3])
            throw new IllegalArgumentException("Invalid bbox: min must be < max — got: " + s);
        return b;
    }

    private static String prop(String key, String def) {
        return System.getProperty(key, def);
    }
}
