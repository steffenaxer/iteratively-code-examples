package io.iteratively.jobEstimator.pipeline;

import io.iteratively.jobEstimator.data.*;
import io.iteratively.jobEstimator.features.FeatureExtractor;
import io.iteratively.jobEstimator.features.FeatureVector;
import io.iteratively.jobEstimator.grid.GridBuilder;
import io.iteratively.jobEstimator.grid.GridCell;
import io.iteratively.jobEstimator.model.SpatialModelSerializer;
import io.iteratively.jobEstimator.model.SpatialModelTrainer;
import io.iteratively.jobEstimator.model.SpatialRegressionModel;
import io.iteratively.jobEstimator.output.GeoJsonPredictionWriter;
import io.iteratively.jobEstimator.validation.CrossValidationResult;
import io.iteratively.jobEstimator.validation.SpatialCrossValidator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.locationtech.jts.geom.Envelope;

import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates the full training workflow:
 * read STATENT → build grid → assign labels → extract features →
 * spatial CV → full-data train → save model → write CV report.
 *
 * <p>The ML backend is injected via {@link SpatialModelTrainer} and {@link SpatialModelSerializer},
 * so swapping XGBoost for another algorithm requires only changing the constructor arguments.
 *
 * <p>Raster paths may be {@code null}; the extractor will substitute 0 for missing data sources.
 */
public final class TrainingPipeline {

    private static final Logger LOG = LogManager.getLogger(TrainingPipeline.class);

    /**
     * Pipeline configuration.
     *
     * @param statentCsvPath    path to the STATENT 2023 CSV (required)
     * @param osmPbfPath        path to the OSM PBF (required)
     * @param ghslBuiltTiffPath GHSL built-up surface GeoTIFF (optional, may be null)
     * @param ghslPopTiffPath   GHSL population GeoTIFF (optional, may be null)
     * @param worldPopTiffPath  WorldPop GeoTIFF (optional, may be null)
     * @param sourceCrsCode     EPSG code of the study-area CRS (e.g. {@code "EPSG:2056"})
     * @param cellSizeMeters    grid cell side length in metres (default 250)
     * @param cvBlocks          spatial blocks per dimension for block CV (default 5 → 25 folds)
     * @param outputDir         directory to write model and CV report into
     * @param bboxMinX          optional study-area bounding box in source CRS (null = full Switzerland)
     * @param bboxMinY          optional study-area bounding box in source CRS
     * @param bboxMaxX          optional study-area bounding box in source CRS
     * @param bboxMaxY          optional study-area bounding box in source CRS
     */
    public record Config(
            Path statentCsvPath,
            Path osmPbfPath,
            Path ghslBuiltTiffPath,
            Path ghslPopTiffPath,
            Path ghslHeightTiffPath,
            Path worldPopTiffPath,
            String sourceCrsCode,
            int cellSizeMeters,
            int cvBlocks,
            Path outputDir,
            Double bboxMinX,
            Double bboxMinY,
            Double bboxMaxX,
            Double bboxMaxY
    ) {
        public boolean hasBbox() {
            return bboxMinX != null && bboxMinY != null && bboxMaxX != null && bboxMaxY != null;
        }

        public static Config switzerlandDefaults(Path statentCsv, Path osmPbf, Path outputDir) {
            return new Config(statentCsv, osmPbf, null, null, null, null, "EPSG:2056", 250, 5, outputDir,
                    null, null, null, null);
        }

        public static Config withBbox(Path statentCsv, Path osmPbf, Path outputDir,
                                      String crsCode, int cellSize, int cvBlocks,
                                      double minX, double minY, double maxX, double maxY) {
            return new Config(statentCsv, osmPbf, null, null, null, null, crsCode, cellSize, cvBlocks, outputDir,
                    minX, minY, maxX, maxY);
        }
    }

    private final Config config;
    private final SpatialModelTrainer trainer;
    private final SpatialModelSerializer serializer;

    public TrainingPipeline(Config config, SpatialModelTrainer trainer, SpatialModelSerializer serializer) {
        this.config     = config;
        this.trainer    = trainer;
        this.serializer = serializer;
    }

    public void run() throws Exception {
        long startMs = System.currentTimeMillis();
        LOG.info("TrainingPipeline starting");

        // 1. Read STATENT ground truth (bounded if bbox is set)
        LOG.info("Reading STATENT: {}", config.statentCsvPath());
        StatentReader statentReader = new StatentReader();
        List<StatentRecord> statent = config.hasBbox()
                ? statentReader.readBounded(config.statentCsvPath(),
                        config.bboxMinX(), config.bboxMinY(), config.bboxMaxX(), config.bboxMaxY())
                : statentReader.read(config.statentCsvPath());

        // 2. Build grid (bounded or full Switzerland) and assign labels
        GridBuilder builder = new GridBuilder();
        List<GridCell> cells = config.hasBbox()
                ? builder.buildGrid(new Envelope(config.bboxMinX(), config.bboxMaxX(),
                                                  config.bboxMinY(), config.bboxMaxY()),
                                    config.cellSizeMeters())
                : builder.buildSwitzerlandGrid(config.cellSizeMeters());
        builder.assignStatentLabels(cells, statent);

        // 3. Extract features (normalisation bounds from actual grid or full Switzerland)
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

        // 4. Spatial block CV
        LOG.info("Running spatial block CV ({}×{} blocks)…", config.cvBlocks(), config.cvBlocks());
        CrossValidationResult cvResult = new SpatialCrossValidator()
                .runBlockCV(cells, trainer, config.cvBlocks());
        cvResult.writeReport(config.outputDir().resolve("cv"));
        LOG.info("CV aggregate: {}", cvResult.aggregateMean());

        // 5. Final model trained on all data
        LOG.info("Training final model on all {} cells…", cells.size());
        Path modelPath = config.outputDir().resolve("model.ubj");
        try (SpatialRegressionModel model = trainer.train(cells)) {
            serializer.save(model, modelPath);

            // 6. Write predictions GeoJSON for the training region
            List<FeatureVector> features = cells.stream().map(GridCell::getFeatures).toList();
            float[] predictions = model.predict(features);
            Path geoJsonPath = config.outputDir().resolve("predictions.geojson");
            new GeoJsonPredictionWriter(config.sourceCrsCode()).write(geoJsonPath, cells, predictions);
        }
        LOG.info("Model saved to {}", modelPath);

        LOG.info("TrainingPipeline complete in {}s", (System.currentTimeMillis() - startMs) / 1000);
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
