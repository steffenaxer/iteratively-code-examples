package io.iteratively.jobEstimator.pipeline;

import io.iteratively.jobEstimator.data.*;
import io.iteratively.jobEstimator.features.FeatureExtractor;
import io.iteratively.jobEstimator.features.FeatureVector;
import io.iteratively.jobEstimator.grid.GridBuilder;
import io.iteratively.jobEstimator.grid.GridCell;
import io.iteratively.jobEstimator.model.SpatialModelSerializer;
import io.iteratively.jobEstimator.model.SpatialRegressionModel;
import io.iteratively.jobEstimator.output.GeoJsonPredictionWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Runs the trained model over a target region (e.g. Braunschweig) and emits
 * GeoJSON and CSV output files.
 *
 * <p>The serializer must match the one used during training (same backend).
 */
public final class InferencePipeline {

    private static final Logger LOG = LogManager.getLogger(InferencePipeline.class);

    /**
     * @param modelPath       path to the saved model file (e.g. {@code model.ubj})
     * @param osmPbfPath      OSM PBF for the target region
     * @param ghslBuiltTiffPath GHSL built-up surface for the target region (optional)
     * @param ghslPopTiffPath  GHSL population for the target region (optional)
     * @param worldPopTiffPath WorldPop for the target region (optional)
     * @param sourceCrsCode   EPSG code of the target CRS (e.g. {@code "EPSG:25832"} for UTM32N)
     * @param bboxMinX        bounding box in the source CRS
     * @param bboxMinY        bounding box in the source CRS
     * @param bboxMaxX        bounding box in the source CRS
     * @param bboxMaxY        bounding box in the source CRS
     * @param cellSizeMeters  grid resolution (should match training, default 250)
     * @param outputDir       directory for GeoJSON and CSV output
     */
    public record Config(
            Path modelPath,
            Path osmPbfPath,
            Path ghslBuiltTiffPath,
            Path ghslPopTiffPath,
            Path ghslHeightTiffPath,
            Path worldPopTiffPath,
            String sourceCrsCode,
            double bboxMinX, double bboxMinY,
            double bboxMaxX, double bboxMaxY,
            int cellSizeMeters,
            Path outputDir,
            Geometry regionGeometry
    ) {
        public Config(Path modelPath, Path osmPbfPath,
                      Path ghslBuiltTiffPath, Path ghslPopTiffPath, Path ghslHeightTiffPath,
                      Path worldPopTiffPath, String sourceCrsCode,
                      double bboxMinX, double bboxMinY, double bboxMaxX, double bboxMaxY,
                      int cellSizeMeters, Path outputDir) {
            this(modelPath, osmPbfPath, ghslBuiltTiffPath, ghslPopTiffPath, ghslHeightTiffPath,
                    worldPopTiffPath, sourceCrsCode, bboxMinX, bboxMinY, bboxMaxX, bboxMaxY,
                    cellSizeMeters, outputDir, null);
        }

        /** Full Switzerland bounding box in LV95 (EPSG:2056). */
        public static Config switzerland(Path modelPath, Path osmPbf, Path outputDir) {
            return new Config(modelPath, osmPbf, null, null, null, null,
                    "EPSG:2056",
                    2485000, 1075000, 2834000, 1296000,
                    250, outputDir);
        }

        /** Braunschweig bounding box in UTM32N (EPSG:25832). */
        public static Config braunschweig(Path modelPath, Path osmPbf, Path outputDir) {
            return new Config(modelPath, osmPbf, null, null, null, null,
                    "EPSG:25832",
                    596000, 5782000, 614000, 5799000,
                    250, outputDir);
        }
    }

    private final Config config;
    private final SpatialModelSerializer serializer;

    public InferencePipeline(Config config, SpatialModelSerializer serializer) {
        this.config     = config;
        this.serializer = serializer;
    }

    public void run() throws Exception {
        long startMs = System.currentTimeMillis();
        LOG.info("InferencePipeline starting for bbox [{},{} → {},{}] in {}",
                config.bboxMinX(), config.bboxMinY(), config.bboxMaxX(), config.bboxMaxY(),
                config.sourceCrsCode());

        // 1. Load model
        try (SpatialRegressionModel model = serializer.load(config.modelPath())) {

            // 2. Build inference grid (optionally clipped to region polygon)
            GridBuilder gridBuilder = new GridBuilder();
            List<GridCell> cells;
            if (config.regionGeometry() != null) {
                cells = gridBuilder.buildGrid(config.regionGeometry(), config.cellSizeMeters());
            } else {
                Envelope bbox = new Envelope(config.bboxMinX(), config.bboxMaxX(),
                        config.bboxMinY(), config.bboxMaxY());
                cells = gridBuilder.buildGrid(bbox, config.cellSizeMeters());
            }
            LOG.info("Inference grid: {} cells", cells.size());

            // 3. Extract features
            OsmFeatureExtractor osmExtractor = new OsmFeatureExtractor();
            osmExtractor.load(config.osmPbfPath());
            try (GhslRasterReader ghslBuilt  = openGhsl(config.ghslBuiltTiffPath(),  GhslRasterReader.GhslLayer.BUILT_SURFACE);
                 GhslRasterReader ghslPop    = openGhsl(config.ghslPopTiffPath(),    GhslRasterReader.GhslLayer.POPULATION);
                 GhslRasterReader ghslHeight = openGhsl(config.ghslHeightTiffPath(), GhslRasterReader.GhslLayer.BUILDING_HEIGHT);
                 WorldPopRasterReader worldPop = openWorldPop(config.worldPopTiffPath())) {

                FeatureExtractor extractor = new FeatureExtractor(
                        osmExtractor, ghslBuilt, ghslPop, ghslHeight, worldPop,
                        config.sourceCrsCode(),
                        config.bboxMinX(), config.bboxMaxX(),
                        config.bboxMinY(), config.bboxMaxY()
                );
                extractor.extractAll(cells);
            } finally {
                osmExtractor.close();
            }

            // 4. Predict
            List<FeatureVector> features = cells.stream().map(GridCell::getFeatures).toList();
            float[] predictions = model.predict(features);
            LOG.info("Predictions generated for {} cells", predictions.length);

            // 5. Write outputs
            Files.createDirectories(config.outputDir());
            new GeoJsonPredictionWriter(config.sourceCrsCode())
                    .write(config.outputDir().resolve("predicted_jobs.geojson"), cells, predictions);
            writeCsv(cells, predictions);
        }

        LOG.info("InferencePipeline complete in {}s", (System.currentTimeMillis() - startMs) / 1000);
    }

    private void writeCsv(List<GridCell> cells, float[] predictions) throws IOException {
        Path out = config.outputDir().resolve("predicted_jobs.csv");
        StringBuilder sb = new StringBuilder("cellId,centerX,centerY,predicted_jobs\n");
        for (int i = 0; i < cells.size(); i++) {
            GridCell c = cells.get(i);
            sb.append(String.format(Locale.ROOT, "%d,%.2f,%.2f,%.2f%n",
                    c.getCellId(), c.getCenterX(), c.getCenterY(), predictions[i]));
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        LOG.info("CSV written to {}", out);
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
