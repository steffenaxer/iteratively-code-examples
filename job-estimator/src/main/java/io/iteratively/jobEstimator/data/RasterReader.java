package io.iteratively.jobEstimator.data;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.geometry.Position;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridCoverage2DReader;
import org.geotools.geometry.Position2D;
import org.geotools.referencing.CRS;

import java.nio.file.Path;

/**
 * Base class for reading single-band GeoTIFF rasters (GHSL, WorldPop, Copernicus).
 *
 * <p>Subclasses open a specific raster file via GeoTools {@link AbstractGridCoverage2DReader}
 * and query it in WGS84 coordinates. All CRS reprojection is handled internally.
 */
abstract class RasterReader implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(RasterReader.class);

    protected static final CoordinateReferenceSystem WGS84;

    static {
        try {
            WGS84 = CRS.decode("EPSG:4326", true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected final Path tiffPath;
    protected GridCoverage2D coverage;
    protected MathTransform wgs84ToRasterCrs;

    protected RasterReader(Path tiffPath) {
        this.tiffPath = tiffPath;
    }

    /**
     * Opens the raster. Must be called before any sampling methods.
     * Subclasses implement this to create the appropriate reader.
     */
    public abstract void open() throws Exception;

    /**
     * Returns the raster value at the given WGS84 point (lon, lat).
     * Returns {@code Double.NaN} if the point falls on a NoData pixel or outside the raster extent.
     */
    public double sampleAt(double lon, double lat) {
        if (coverage == null) throw new IllegalStateException("Raster not opened. Call open() first.");
        try {
            Position pos;
            if (wgs84ToRasterCrs != null) {
                Position2D src = new Position2D(WGS84, lon, lat);
                Position2D dst = new Position2D();
                wgs84ToRasterCrs.transform(src, dst);
                pos = dst;
            } else {
                pos = new Position2D(WGS84, lon, lat);
            }
            float[] result = (float[]) coverage.evaluate(pos, (float[]) null);
            float v = result[0];
            return isNoData(v) ? Double.NaN : v;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /**
     * Computes the mean raster value within the given WGS84 bounding box.
     * Samples all pixels whose centroids fall inside the box.
     * Returns {@code Double.NaN} if no valid pixels are found.
     *
     * <p>Uses a simple grid-sampling approach: divides the bbox into a regular
     * sampling grid and queries each sample point. Efficient for small bboxes
     * (250 m cell → ~6 pixels at 100 m resolution).
     */
    public double meanInBounds(double minLon, double minLat, double maxLon, double maxLat) {
        if (coverage == null) throw new IllegalStateException("Raster not opened. Call open() first.");

        // Sample at ~5 points per pixel width (sufficient for 250m cell / 100m raster)
        int samples = 5;
        double dLon = (maxLon - minLon) / samples;
        double dLat = (maxLat - minLat) / samples;

        double sum = 0;
        int count = 0;
        for (int i = 0; i < samples; i++) {
            for (int j = 0; j < samples; j++) {
                double lon = minLon + (i + 0.5) * dLon;
                double lat = minLat + (j + 0.5) * dLat;
                double v = sampleAt(lon, lat);
                if (!Double.isNaN(v)) {
                    sum += v;
                    count++;
                }
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    /**
     * Computes the sum of raster pixel values within the bounding box.
     * Useful for population counts (sum, not average).
     */
    public double sumInBounds(double minLon, double minLat, double maxLon, double maxLat) {
        if (coverage == null) throw new IllegalStateException("Raster not opened. Call open() first.");

        int samples = 5;
        double dLon = (maxLon - minLon) / samples;
        double dLat = (maxLat - minLat) / samples;

        double sum = 0;
        for (int i = 0; i < samples; i++) {
            for (int j = 0; j < samples; j++) {
                double lon = minLon + (i + 0.5) * dLon;
                double lat = minLat + (j + 0.5) * dLat;
                double v = sampleAt(lon, lat);
                if (!Double.isNaN(v) && v > 0) {
                    sum += v;
                }
            }
        }
        return sum;
    }

    /** Override to specify the NoData value for a particular raster. Default: -9999 and negative. */
    protected boolean isNoData(float value) {
        return Float.isNaN(value) || value <= -9999f;
    }

    @Override
    public void close() {
        if (coverage != null) {
            coverage.dispose(false);
            coverage = null;
        }
    }
}
