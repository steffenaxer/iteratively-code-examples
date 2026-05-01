package io.iteratively.jobEstimator.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidationMetricsTest {

    @Test
    void perfectPredictionsGiveR2One() {
        double[] actual    = {10, 20, 30, 40, 50};
        double[] predicted = {10, 20, 30, 40, 50};
        ValidationMetrics m = ValidationMetrics.compute(actual, predicted);
        assertEquals(0.0, m.mae(),    1e-9);
        assertEquals(0.0, m.rmse(),   1e-9);
        assertEquals(1.0, m.r2(),     1e-9);
        assertEquals(0.0, m.mape(),   1e-9);
        assertEquals(0.0, m.logRmse(), 1e-9);
    }

    @Test
    void predictionsNonNegativeClamped() {
        double[] actual    = {100, 200};
        double[] predicted = {-10, 200}; // negative prediction should still work in metrics
        assertDoesNotThrow(() -> ValidationMetrics.compute(actual, predicted));
    }

    @Test
    void mapeExcludesZeroActuals() {
        double[] actual    = {0, 10, 20};
        double[] predicted = {5, 10, 20};
        ValidationMetrics m = ValidationMetrics.compute(actual, predicted);
        // MAPE only over indices 1 and 2 (both perfect) → 0%
        assertEquals(0.0, m.mape(), 1e-9);
    }

    @Test
    void maeMeansAbsoluteError() {
        double[] actual    = {10, 20, 30};
        double[] predicted = {12, 18, 33};
        ValidationMetrics m = ValidationMetrics.compute(actual, predicted);
        assertEquals((2 + 2 + 3) / 3.0, m.mae(), 1e-9);
    }

    @Test
    void throwsOnMismatchedArrays() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationMetrics.compute(new double[]{1, 2}, new double[]{1}));
    }

    @Test
    void throwsOnEmptyArrays() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationMetrics.compute(new double[0], new double[0]));
    }

    @Test
    void toStringContainsAllMetricNames() {
        ValidationMetrics m = ValidationMetrics.compute(new double[]{10}, new double[]{10});
        String s = m.toString();
        assertTrue(s.contains("MAE"));
        assertTrue(s.contains("RMSE"));
        assertTrue(s.contains("R²"));
        assertTrue(s.contains("MAPE"));
        assertTrue(s.contains("logRMSE"));
    }
}
