package io.iteratively.jobEstimator.features;

import io.iteratively.jobEstimator.data.GhslRasterReader;
import io.iteratively.jobEstimator.data.OsmFeatureExtractor;
import io.iteratively.jobEstimator.data.WorldPopRasterReader;
import io.iteratively.jobEstimator.grid.GridBuilder;
import io.iteratively.jobEstimator.grid.GridCell;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FeatureExtractorTest {

    /**
     * Creates a FeatureExtractor with real (empty) OSM extractor and no rasters,
     * using LV95 as source CRS.
     */
    private FeatureExtractor makeExtractor(double xMin, double xMax, double yMin, double yMax) {
        OsmFeatureExtractor osm = new OsmFeatureExtractor(); // unloaded → returns zeros
        return new FeatureExtractor(osm, null, null, null, null,
                "EPSG:2056", xMin, xMax, yMin, yMax);
    }

    @Test
    void testExtractFeaturesReturnsCorrectLength() {
        FeatureExtractor fe = makeExtractor(2480000, 2840000, 1070000, 1300000);
        GridCell cell = new GridCell(0, 2600000, 1200000,
                new Envelope(2599875, 2600125, 1199875, 1200125));
        FeatureVector fv = fe.extractFeatures(cell);
        assertNotNull(fv);
        assertEquals(FeatureVector.FEATURE_COUNT, fv.valuesUnsafe().length);
    }

    @Test
    void testNormalisedCoordsInUnitRange() {
        double xMin = 2480000, xMax = 2840000, yMin = 1070000, yMax = 1300000;
        FeatureExtractor fe = makeExtractor(xMin, xMax, yMin, yMax);

        // Cell at bounding box min corner
        GridCell cellMin = new GridCell(0, xMin, yMin,
                new Envelope(xMin, xMin + 250, yMin, yMin + 250));
        FeatureVector fvMin = fe.extractFeatures(cellMin);
        assertEquals(0.0f, fvMin.valuesUnsafe()[FeatureVector.F_CELL_CENTER_X_NORM], 0.01f);
        assertEquals(0.0f, fvMin.valuesUnsafe()[FeatureVector.F_CELL_CENTER_Y_NORM], 0.01f);

        // Cell at bounding box max corner
        GridCell cellMax = new GridCell(1, xMax, yMax,
                new Envelope(xMax - 250, xMax, yMax - 250, yMax));
        FeatureVector fvMax = fe.extractFeatures(cellMax);
        assertEquals(1.0f, fvMax.valuesUnsafe()[FeatureVector.F_CELL_CENTER_X_NORM], 0.01f);
        assertEquals(1.0f, fvMax.valuesUnsafe()[FeatureVector.F_CELL_CENTER_Y_NORM], 0.01f);
    }

    @Test
    void testExtractAllUpdatesAllCells() {
        FeatureExtractor fe = makeExtractor(2480000, 2840000, 1070000, 1300000);
        GridBuilder builder = new GridBuilder();
        List<GridCell> cells = builder.buildGrid(
                new Envelope(2600000, 2601000, 1200000, 1201000), 250);

        fe.extractAll(cells);

        for (GridCell cell : cells) {
            assertTrue(cell.hasFeatures(), "Every cell should have features after extractAll");
        }
    }

    @Test
    void testInvalidCrsThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new FeatureExtractor(new OsmFeatureExtractor(), null, null, null, null,
                        "EPSG:9999999", 0, 1, 0, 1));
    }
}
