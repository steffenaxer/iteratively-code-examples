package io.iteratively.jobEstimator.validation;

/**
 * Immutable snapshot of regression quality metrics for one CV fold or the full dataset.
 *
 * <p>MAPE excludes cells where {@code actual == 0} to avoid division by zero.
 * logRMSE is computed on the {@code log1p}-transformed scale and matches the XGBoost
 * training objective, making it a reliable cross-fold comparator.
 */
public record ValidationMetrics(double mae, double rmse, double r2, double mape, double logRmse) {

    /**
     * Computes all metrics from parallel arrays of ground-truth and predicted job counts.
     *
     * @param actual    ground-truth employment counts (non-negative)
     * @param predicted model predictions (non-negative, back-transformed)
     */
    public static ValidationMetrics compute(double[] actual, double[] predicted) {
        if (actual.length == 0 || actual.length != predicted.length) {
            throw new IllegalArgumentException(
                    "actual and predicted must have equal non-zero length, got " + actual.length + " vs " + predicted.length);
        }
        int n = actual.length;

        double actualMean = 0;
        for (double a : actual) actualMean += a;
        actualMean /= n;

        double sumAe = 0, sumSe = 0, sumLogSe = 0, sumApe = 0;
        int mapeCount = 0;

        for (int i = 0; i < n; i++) {
            double diff    = actual[i] - predicted[i];
            double logDiff = Math.log1p(actual[i]) - Math.log1p(Math.max(0, predicted[i]));
            sumAe    += Math.abs(diff);
            sumSe    += diff * diff;
            sumLogSe += logDiff * logDiff;
            if (actual[i] > 0) {
                sumApe += Math.abs(diff) / actual[i];
                mapeCount++;
            }
        }

        double ssTot = 0;
        for (double a : actual) { double d = a - actualMean; ssTot += d * d; }

        return new ValidationMetrics(
                sumAe / n,
                Math.sqrt(sumSe / n),
                ssTot > 0 ? 1.0 - sumSe / ssTot : 0.0,
                mapeCount > 0 ? (sumApe / mapeCount) * 100.0 : Double.NaN,
                Math.sqrt(sumLogSe / n)
        );
    }

    @Override
    public String toString() {
        return String.format("MAE=%.2f  RMSE=%.2f  R²=%.4f  MAPE=%.1f%%  logRMSE=%.4f",
                mae, rmse, r2, mape, logRmse);
    }
}
