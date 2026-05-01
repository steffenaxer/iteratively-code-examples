package io.iteratively.jobEstimator.pipeline;

import io.iteratively.jobEstimator.model.xgboost.XGBoostModelSerializer;
import io.iteratively.jobEstimator.model.xgboost.XGBoostModelTrainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the full training pipeline.
 * Skipped automatically when the STATENT data directory is absent.
 *
 * <p>To run locally:
 * <pre>
 *   mvn test -pl job-estimator -Dtest=TrainingPipelineIT -Pintegration
 * </pre>
 */
@DisabledIf("dataAbsent")
class TrainingPipelineIT {

    static boolean dataAbsent() {
        return !Path.of("../data/ag-b-00.03-22-STATENT2023/STATENT_2023.csv").toFile().exists()
                || !Path.of("../data/osm/switzerland-latest.osm.pbf").toFile().exists();
    }

    @Test
    void runSmallZugBbox(@TempDir Path outputDir) throws Exception {
        Path statentCsv = Path.of("../data/ag-b-00.03-22-STATENT2023/STATENT_2023.csv");
        Path osmPbf     = Path.of("../data/osm/switzerland-latest.osm.pbf");

        // Kanton Zug bbox in LV95 — ~30×30 km, only ~14 400 cells at 250m
        TrainingPipeline.Config config = TrainingPipeline.Config.withBbox(
                statentCsv, osmPbf, outputDir,
                "EPSG:2056", 250,
                2,           // 2×2 = 4 CV folds for speed
                2665000, 1209000, 2705000, 1244000
        );

        XGBoostModelTrainer trainer = new XGBoostModelTrainer(
                XGBoostModelTrainer.defaultParams(), 50, 0, 0.0f);

        new TrainingPipeline(config, trainer, new XGBoostModelSerializer()).run();

        assertTrue(Files.exists(outputDir.resolve("model.ubj")));
        assertTrue(Files.exists(outputDir.resolve("cv/cv_metrics.csv")));
        assertTrue(Files.exists(outputDir.resolve("cv/cv_report.json")));

        long rows;
        try (var lines = Files.lines(outputDir.resolve("cv/cv_metrics.csv"))) {
            rows = lines.count();
        }
        assertTrue(rows > 1, "cv_metrics.csv must have at least one data row");
    }
}
