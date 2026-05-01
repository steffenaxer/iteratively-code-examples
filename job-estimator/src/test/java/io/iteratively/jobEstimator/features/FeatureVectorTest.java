package io.iteratively.jobEstimator.features;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeatureVectorTest {

    @Test
    void testFeatureCount() {
        assertEquals(37, FeatureVector.FEATURE_COUNT);
    }

    @Test
    void testNamesLengthEqualsFeatureCount() {
        assertEquals(FeatureVector.FEATURE_COUNT, FeatureVector.featureNames().length);
    }

    @Test
    void testConstructorRequiresExactLength() {
        assertThrows(IllegalArgumentException.class, () -> new FeatureVector(new float[36]));
        assertThrows(IllegalArgumentException.class, () -> new FeatureVector(new float[38]));
        assertDoesNotThrow(() -> new FeatureVector(new float[37]));
    }

    @Test
    void testValuesReturnsDefensiveCopy() {
        float[] original = new float[37];
        original[0] = 42f;
        FeatureVector fv = new FeatureVector(original);

        float[] copy = fv.values();
        copy[0] = 99f;  // mutate the copy

        // original FeatureVector should be unaffected
        assertEquals(42f, fv.valuesUnsafe()[0], 0.001f);
    }

    @Test
    void testConstructorCopiesInput() {
        float[] original = new float[37];
        original[0] = 1f;
        FeatureVector fv = new FeatureVector(original);

        original[0] = 999f;  // mutate source after construction
        assertEquals(1f, fv.valuesUnsafe()[0], 0.001f);
    }

    @Test
    void testLastIndexIsYNorm() {
        assertEquals(36, FeatureVector.F_CELL_CENTER_Y_NORM);
        assertEquals(FeatureVector.FEATURE_COUNT - 1, FeatureVector.F_CELL_CENTER_Y_NORM);
    }
}
