package io.iteratively.jobEstimator.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Aggregated result from a spatial cross-validation run.
 *
 * <p>Provides per-fold metrics and convenience aggregation. The {@link #writeReport} method
 * emits the artefacts expected by downstream tooling (QGIS, notebooks).
 */
public record CrossValidationResult(List<ValidationMetrics> foldMetrics) {

    /** Mean metrics across all folds (simple average, not weighted by fold size). */
    public ValidationMetrics aggregateMean() {
        int n = foldMetrics.size();
        if (n == 0) return new ValidationMetrics(0, 0, 0, 0, 0);
        double mae = 0, rmse = 0, r2 = 0, mape = 0, logRmse = 0;
        for (ValidationMetrics m : foldMetrics) {
            mae     += m.mae();
            rmse    += m.rmse();
            r2      += m.r2();
            mape    += m.mape();
            logRmse += m.logRmse();
        }
        return new ValidationMetrics(mae/n, rmse/n, r2/n, mape/n, logRmse/n);
    }

    /**
     * Writes three output files to {@code outputDir}:
     * <ol>
     *   <li>{@code cv_metrics.csv} — one row per fold</li>
     *   <li>{@code cv_report.json} — aggregate mean metrics</li>
     * </ol>
     */
    public void writeReport(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        // cv_metrics.csv
        StringBuilder csv = new StringBuilder("fold,mae,rmse,r2,mape,logRmse\n");
        for (int i = 0; i < foldMetrics.size(); i++) {
            ValidationMetrics m = foldMetrics.get(i);
            csv.append(String.format(java.util.Locale.ROOT,
                    "%d,%.4f,%.4f,%.6f,%.2f,%.6f%n",
                    i + 1, m.mae(), m.rmse(), m.r2(), m.mape(), m.logRmse()));
        }
        Files.writeString(outputDir.resolve("cv_metrics.csv"), csv.toString(), StandardCharsets.UTF_8);

        // cv_report.json
        ValidationMetrics agg = aggregateMean();
        String json = String.format(java.util.Locale.ROOT,
                """
                {
                  "folds": %d,
                  "mean_mae": %.4f,
                  "mean_rmse": %.4f,
                  "mean_r2": %.6f,
                  "mean_mape": %.2f,
                  "mean_logRmse": %.6f
                }""",
                foldMetrics.size(), agg.mae(), agg.rmse(), agg.r2(), agg.mape(), agg.logRmse());
        Files.writeString(outputDir.resolve("cv_report.json"), json, StandardCharsets.UTF_8);
    }
}
