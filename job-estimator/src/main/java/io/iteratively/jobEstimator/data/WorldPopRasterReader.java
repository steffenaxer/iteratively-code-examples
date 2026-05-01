package io.iteratively.jobEstimator.data;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.coverage.grid.io.AbstractGridCoverage2DReader;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.referencing.CRS;

import java.nio.file.Path;

/**
 * Reads WorldPop population GeoTIFF rasters.
 *
 * <p>WorldPop constrained 100 m products use WGS84 (EPSG:4326) directly,
 * so no reprojection is needed for query coordinates.
 *
 * <p>Values represent estimated population count per 100 m pixel.
 */
public final class WorldPopRasterReader extends RasterReader {

    private static final Logger LOG = LogManager.getLogger(WorldPopRasterReader.class);

    public WorldPopRasterReader(Path tiffPath) {
        super(tiffPath);
    }

    @Override
    public void open() throws Exception {
        AbstractGridCoverage2DReader reader = new GeoTiffReader(tiffPath.toFile());
        coverage = reader.read(null);

        CoordinateReferenceSystem rasterCrs = coverage.getCoordinateReferenceSystem2D();
        // WorldPop is in WGS84 → no transform needed; set to null to use coordinates as-is
        boolean isWgs84 = CRS.equalsIgnoreMetadata(rasterCrs, WGS84);
        wgs84ToRasterCrs = isWgs84 ? null : CRS.findMathTransform(WGS84, rasterCrs, true);

        LOG.info("Opened WorldPop raster: {} (CRS: {}, wgs84Native={})",
                tiffPath.getFileName(), rasterCrs.getName(), isWgs84);
    }
}
