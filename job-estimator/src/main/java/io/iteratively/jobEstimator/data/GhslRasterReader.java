package io.iteratively.jobEstimator.data;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.coverage.grid.io.AbstractGridCoverage2DReader;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.referencing.CRS;

import java.nio.file.Path;

/**
 * Reads GHSL (Global Human Settlement Layer) GeoTIFF rasters.
 *
 * <p>Supports:
 * <ul>
 *   <li>{@link GhslLayer#BUILT_SURFACE} — GHS_BUILT_S, m² of built-up surface per 100 m pixel</li>
 *   <li>{@link GhslLayer#POPULATION}    — GHS_POP, population count per 100 m pixel</li>
 * </ul>
 *
 * <p>GHSL R2023A products use Mollweide projection (EPSG:54009). The reader
 * transforms query coordinates from WGS84 to the raster CRS automatically.
 */
public final class GhslRasterReader extends RasterReader {

    private static final Logger LOG = LogManager.getLogger(GhslRasterReader.class);

    public enum GhslLayer {
        BUILT_SURFACE,
        POPULATION,
        BUILDING_HEIGHT
    }

    private final GhslLayer layer;

    public GhslRasterReader(Path tiffPath, GhslLayer layer) {
        super(tiffPath);
        this.layer = layer;
    }

    @Override
    public void open() throws Exception {
        AbstractGridCoverage2DReader reader = new GeoTiffReader(tiffPath.toFile());
        coverage = reader.read(null);

        CoordinateReferenceSystem rasterCrs = coverage.getCoordinateReferenceSystem2D();
        wgs84ToRasterCrs = CRS.findMathTransform(WGS84, rasterCrs, true);

        LOG.info("Opened GHSL {} raster: {} (CRS: {})",
                layer, tiffPath.getFileName(), rasterCrs.getName());
    }

    public GhslLayer getLayer() {
        return layer;
    }
}
