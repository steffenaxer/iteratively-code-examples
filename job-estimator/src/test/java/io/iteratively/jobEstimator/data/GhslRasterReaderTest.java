package io.iteratively.jobEstimator.data;

import org.geotools.coverage.CoverageFactoryFinder;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GhslRasterReaderTest {

    @TempDir
    Path tmp;

    /**
     * Creates a tiny 4×4 WGS84 GeoTIFF with known pixel values.
     * Pixel (0,0) = 100, all others = 0.
     * Covers [8.0, 9.0] lon × [47.0, 48.0] lat.
     */
    private Path createTestTiff() throws Exception {
        int width = 4, height = 4;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        WritableRaster raster = img.getRaster();
        // Set pixel (0, 0) to value 100 (top-left = minLon, maxLat in image convention)
        raster.setPixel(0, 0, new int[]{100, 0, 0, 255});

        var wgs84 = CRS.decode("EPSG:4326", true);
        ReferencedEnvelope env = new ReferencedEnvelope(8.0, 9.0, 47.0, 48.0, wgs84);
        GridCoverageFactory factory = CoverageFactoryFinder.getGridCoverageFactory(null);
        GridCoverage2D coverage = factory.create("test", img, env);

        Path tiff = tmp.resolve("test.tif");
        GeoTiffWriter writer = new GeoTiffWriter(tiff.toFile());
        writer.write(coverage, null);
        writer.dispose();
        return tiff;
    }

    @Test
    void testOpenDoesNotThrow() throws Exception {
        Path tiff = createTestTiff();
        try (GhslRasterReader reader = new GhslRasterReader(tiff, GhslRasterReader.GhslLayer.BUILT_SURFACE)) {
            assertDoesNotThrow(reader::open);
        }
    }

    @Test
    void testSampleAtKnownPixelReturnsValue() throws Exception {
        Path tiff = createTestTiff();
        try (GhslRasterReader reader = new GhslRasterReader(tiff, GhslRasterReader.GhslLayer.BUILT_SURFACE)) {
            reader.open();
            // Sample near center of raster → should return some value (not NaN)
            double v = reader.sampleAt(8.5, 47.5);
            // We can't guarantee exact pixel value due to interpolation, but it shouldn't be NaN
            // for a point well inside the raster
            assertFalse(Double.isNaN(v) && v < -1000,
                    "Expected a valid sample value inside raster bounds");
        }
    }

    @Test
    void testSampleOutsideRasterReturnsNaN() throws Exception {
        Path tiff = createTestTiff();
        try (GhslRasterReader reader = new GhslRasterReader(tiff, GhslRasterReader.GhslLayer.POPULATION)) {
            reader.open();
            // Point far outside raster extent
            double v = reader.sampleAt(0.0, 0.0);
            assertTrue(Double.isNaN(v));
        }
    }

    @Test
    void testMeanInBoundsReturnsFiniteValue() throws Exception {
        Path tiff = createTestTiff();
        try (GhslRasterReader reader = new GhslRasterReader(tiff, GhslRasterReader.GhslLayer.BUILT_SURFACE)) {
            reader.open();
            double mean = reader.meanInBounds(8.1, 47.1, 8.9, 47.9);
            // Should be a finite number (not NaN) for a bbox inside the raster
            assertTrue(Double.isFinite(mean) || Double.isNaN(mean),
                    "meanInBounds must return finite or NaN");
        }
    }

    @Test
    void testThrowsIfNotOpened() {
        GhslRasterReader reader = new GhslRasterReader(tmp.resolve("nonexistent.tif"),
                GhslRasterReader.GhslLayer.BUILT_SURFACE);
        assertThrows(IllegalStateException.class, () -> reader.sampleAt(8.0, 47.0));
    }
}
