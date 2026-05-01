package io.iteratively.jobEstimator.data;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OsmFeatureExtractor using Mockito-style approach:
 * tests focus on the query logic with a pre-loaded extractor (loaded from
 * minimal in-memory data) or simply test the classification logic.
 *
 * <p>Full PBF-loading integration is covered by TrainingPipelineIT when real data is present.
 */
class OsmFeatureExtractorTest {

    @Test
    void testQueryOnUnloadedExtractorDoesNotThrow() {
        // An extractor that was never loaded should still return an all-zero map
        OsmFeatureExtractor extractor = new OsmFeatureExtractor();
        Envelope bounds = new Envelope(8.0, 8.25, 47.0, 47.25);
        Map<OsmFeatureExtractor.OsmCategory, Double> result = extractor.queryCell(bounds);

        assertNotNull(result);
        assertEquals(OsmFeatureExtractor.OsmCategory.values().length, result.size());
        for (double v : result.values()) {
            assertEquals(0.0, v, 0.001);
        }
    }

    @Test
    void testAllCategoriesPresent() {
        OsmFeatureExtractor extractor = new OsmFeatureExtractor();
        Map<OsmFeatureExtractor.OsmCategory, Double> result =
                extractor.queryCell(new Envelope(8.0, 8.25, 47.0, 47.25));

        for (OsmFeatureExtractor.OsmCategory cat : OsmFeatureExtractor.OsmCategory.values()) {
            assertTrue(result.containsKey(cat), "Missing category: " + cat);
        }
    }

    @Test
    void testCategoryCount() {
        // 23 categories total (6 building + 5 POI + 5 road + 4 landuse + 3 derived)
        assertEquals(23, OsmFeatureExtractor.OsmCategory.values().length);
    }

    @Test
    void testClosedExtractorReturnsZeros() {
        OsmFeatureExtractor extractor = new OsmFeatureExtractor();
        extractor.close();
        Map<OsmFeatureExtractor.OsmCategory, Double> result =
                extractor.queryCell(new Envelope(8.0, 8.25, 47.0, 47.25));
        for (double v : result.values()) {
            assertEquals(0.0, v, 0.001);
        }
    }
}
