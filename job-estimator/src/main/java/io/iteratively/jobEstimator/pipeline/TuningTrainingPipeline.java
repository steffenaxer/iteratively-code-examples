package io.iteratively.jobEstimator.pipeline;

import io.iteratively.jobEstimator.data.*;
import io.iteratively.jobEstimator.features.FeatureExtractor;
import io.iteratively.jobEstimator.features.FeatureVector;
import io.iteratively.jobEstimator.grid.GridBuilder;
import io.iteratively.jobEstimator.grid.GridCell;
import io.iteratively.jobEstimator.model.SpatialModelSerializer;
import io.iteratively.jobEstimator.model.SpatialModelTrainer;
import io.iteratively.jobEstimator.model.SpatialRegressionModel;
import io.iteratively.jobEstimator.model.xgboost.HyperparameterSearch;
import io.iteratively.jobEstimator.model.xgboost.HyperparameterSearch.SearchMode;
import io.iteratively.jobEstimator.model.xgboost.TwoStageModelTrainer;
import io.iteratively.jobEstimator.model.xgboost.XGBoostModelTrainer;
import io.iteratively.jobEstimator.output.GeoJsonPredictionWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Envelope;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Training pipeline variant that first runs hyperparameter tuning,
 * then trains the final model with the best parameters found.
 */
public final class TuningTrainingPipeline {

    private static final Logger LOG = LogManager.getLogger(TuningTrainingPipeline.class);

    private final TrainingPipeline.Config config;
    private final SpatialModelSerializer serializer;
    private final String modelType;
    private final int cvBlocks;
    private final SearchMode searchMode;

    public TuningTrainingPipeline(TrainingPipeline.Config config,
                                  SpatialModelSerializer serializer,
                                  String modelType,
                                  int cvBlocks) {
        this(config, serializer, modelType, cvBlocks, SearchMode.FULL);
    }

    public TuningTrainingPipeline(TrainingPipeline.Config config,
                                  SpatialModelSerializer serializer,
                                  String modelType,
                                  int cvBlocks,
                                  SearchMode searchMode) {
        this.config = config;
        this.serializer = serializer;
        this.modelType = modelType;
        this.cvBlocks = cvBlocks;
        this.searchMode = searchMode;
    }

    public void run() throws Exception {
        long startMs = System.currentTimeMillis();
        LOG.info("TuningTrainingPipeline starting");

        // 1. Read STATENT
        StatentReader statentReader = new StatentReader();
        List<StatentRecord> statent = config.hasBbox()
                ? statentReader.readBounded(config.statentCsvPath(),
                        config.bboxMinX(), config.bboxMinY(), config.bboxMaxX(), config.bboxMaxY())
                : statentReader.read(config.statentCsvPath());

        // 2. Build grid and assign labels
        GridBuilder builder = new GridBuilder();
        List<GridCell> cells = config.hasBbox()
                ? builder.buildGrid(new Envelope(config.bboxMinX(), config.bboxMaxX(),
                                                  config.bboxMinY(), config.bboxMaxY()),
                                    config.cellSizeMeters())
                : builder.buildSwitzerlandGrid(config.cellSizeMeters());
        builder.assignStatentLabels(cells, statent);

        // 3. Extract features
        double normXMin = config.hasBbox() ? config.bboxMinX() : GridBuilder.CH_E_MIN;
        double normXMax = config.hasBbox() ? config.bboxMaxX() : GridBuilder.CH_E_MAX;
        double normYMin = config.hasBbox() ? config.bboxMinY() : GridBuilder.CH_N_MIN;
        double normYMax = config.hasBbox() ? config.bboxMaxY() : GridBuilder.CH_N_MAX;

        OsmFeatureExtractor osmExtractor = new OsmFeatureExtractor();
        osmExtractor.load(config.osmPbfPath());
        try (GhslRasterReader ghslBuilt  = openGhsl(config.ghslBuiltTiffPath(),  GhslRasterReader.GhslLayer.BUILT_SURFACE);
             GhslRasterReader ghslPop    = openGhsl(config.ghslPopTiffPath(),    GhslRasterReader.GhslLayer.POPULATION);
             GhslRasterReader ghslHeight = openGhsl(config.ghslHeightTiffPath(), GhslRasterReader.GhslLayer.BUILDING_HEIGHT);
             WorldPopRasterReader worldPop = openWorldPop(config.worldPopTiffPath())) {

            FeatureExtractor extractor = new FeatureExtractor(
                    osmExtractor, ghslBuilt, ghslPop, ghslHeight, worldPop,
                    config.sourceCrsCode(),
                    normXMin, normXMax, normYMin, normYMax
            );
            extractor.extractAll(cells);
        } finally {
            osmExtractor.close();
        }

        // 4. Hyperparameter tuning
        LOG.info("Running hyperparameter search...");
        List<GridCell> labeledCells = cells.stream()
                .filter(c -> c.hasLabel() && c.hasFeatures())
                .toList();

        HyperparameterSearch search = new HyperparameterSearch(labeledCells, cvBlocks, config.outputDir(), searchMode);
        HyperparameterSearch.Result tuneResult = search.run();
        LOG.info("Best hyperparameters found: R²={} params={}",
                String.format("%.4f", tuneResult.metrics().r2()), tuneResult.bestParams());

        // 5. Train final model with best params
        SpatialModelTrainer finalTrainer;
        if ("twostage".equals(modelType)) {
            finalTrainer = TwoStageModelTrainer.withParams(tuneResult.bestParams(), tuneResult.bestRounds());
        } else {
            finalTrainer = new XGBoostModelTrainer(tuneResult.bestParams(), tuneResult.bestRounds(), 30, 0.1f);
        }

        Path modelPath = config.outputDir().resolve("model.ubj");
        try (SpatialRegressionModel model = finalTrainer.train(cells)) {
            serializer.save(model, modelPath);

            List<FeatureVector> features = cells.stream().map(GridCell::getFeatures).toList();
            float[] predictions = model.predict(features);
            Path geoJsonPath = config.outputDir().resolve("predictions.geojson");
            new GeoJsonPredictionWriter(config.sourceCrsCode()).write(geoJsonPath, cells, predictions);
        }

        LOG.info("Model saved to {}", modelPath);
        LOG.info("TuningTrainingPipeline complete in {}s", (System.currentTimeMillis() - startMs) / 1000);
    }

    private GhslRasterReader openGhsl(Path path, GhslRasterReader.GhslLayer layer) throws Exception {
        if (path == null || !path.toFile().exists()) return null;
        GhslRasterReader r = new GhslRasterReader(path, layer);
        r.open();
        return r;
    }

    private WorldPopRasterReader openWorldPop(Path path) throws Exception {
        if (path == null || !path.toFile().exists()) return null;
        WorldPopRasterReader r = new WorldPopRasterReader(path);
        r.open();
        return r;
    }
}
