package io.iteratively.jobEstimator.output;

import io.iteratively.jobEstimator.grid.GridCell;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Envelope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes grid-cell predictions as a WGS84 GeoJSON FeatureCollection.
 *
 * <p>Each cell is emitted as a square Polygon in WGS84 (EPSG:4326) so the
 * output can be opened directly in QGIS, kepler.gl, or any GeoJSON viewer.
 *
 * <p>Properties per feature:
 * <ul>
 *   <li>{@code predicted_jobs} — estimated absolute job count</li>
 *   <li>{@code label}          — STATENT ground-truth (NaN if not a training cell)</li>
 *   <li>{@code cellId}         — internal grid cell identifier</li>
 * </ul>
 */
public final class GeoJsonPredictionWriter {

    private static final Logger LOG = LogManager.getLogger(GeoJsonPredictionWriter.class);

    private final MathTransform toWgs84;

    /**
     * @param sourceCrsCode EPSG code of the grid's native CRS, e.g. {@code "EPSG:2056"}
     */
    public GeoJsonPredictionWriter(String sourceCrsCode) {
        try {
            CoordinateReferenceSystem src  = CRS.decode(sourceCrsCode, true);
            CoordinateReferenceSystem wgs84 = CRS.decode("EPSG:4326",   true);
            this.toWgs84 = CRS.findMathTransform(src, wgs84, true);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot build transform for CRS: " + sourceCrsCode, e);
        }
    }

    /**
     * Writes predictions to a {@code .geojson} file.
     *
     * @param outputPath path to write the GeoJSON file
     * @param cells      grid cells (same order as {@code predictions})
     * @param predictions predicted job counts; index matches {@code cells}
     */
    public void write(Path outputPath, List<GridCell> cells, float[] predictions) throws IOException {
        StringBuilder sb = new StringBuilder(cells.size() * 200);
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[\n");

        int written = 0;
        for (int i = 0; i < cells.size(); i++) {
            GridCell cell = cells.get(i);
            double pred = predictions[i];

            // Skip zero-label / zero-prediction cells to keep file size manageable
            // (keep if prediction > 0 or ground truth > 0)
            boolean hasLabel  = cell.hasLabel() && cell.getLabel() > 0;
            boolean hasPred   = pred > 0.5;
            if (!hasLabel && !hasPred) continue;

            double[] ring = polygonRing(cell.getBounds());
            if (ring == null) continue;

            if (written > 0) sb.append(",\n");
            sb.append(String.format(Locale.ROOT,
                    "{\"type\":\"Feature\","
                    + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[["
                    + "[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f]"
                    + "]]},"
                    + "\"properties\":{\"cellId\":%d,\"predicted_jobs\":%.1f,\"label\":%.1f}}",
                    ring[0], ring[1], ring[2], ring[3], ring[4], ring[5],
                    ring[6], ring[7], ring[8], ring[9],
                    cell.getCellId(),
                    pred,
                    cell.hasLabel() ? cell.getLabel() : Double.NaN
            ));
            written++;
        }

        sb.append("\n]}");
        Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
        LOG.info("GeoJSON written: {} features → {}", written, outputPath);
    }

    /** Transforms the 5 polygon ring corners (SW→SE→NE→NW→SW) from source CRS to WGS84 lon/lat. */
    private double[] polygonRing(Envelope b) {
        // source coords: SW SE NE NW SW (closed ring)
        double[] pts = {
            b.getMinX(), b.getMinY(),
            b.getMaxX(), b.getMinY(),
            b.getMaxX(), b.getMaxY(),
            b.getMinX(), b.getMaxY(),
            b.getMinX(), b.getMinY()
        };
        try {
            toWgs84.transform(pts, 0, pts, 0, 5);
            return pts; // now lon/lat pairs
        } catch (Exception e) {
            return null;
        }
    }
}
