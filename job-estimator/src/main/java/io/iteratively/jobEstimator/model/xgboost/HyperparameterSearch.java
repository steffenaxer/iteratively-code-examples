package io.iteratively.jobEstimator.model.xgboost;

import io.iteratively.jobEstimator.grid.GridCell;
import io.iteratively.jobEstimator.validation.CrossValidationResult;
import io.iteratively.jobEstimator.validation.SpatialCrossValidator;
import io.iteratively.jobEstimator.validation.ValidationMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class HyperparameterSearch {

    private static final Logger LOG = LogManager.getLogger(HyperparameterSearch.class);

    public record Result(Map<String, Object> bestParams, int bestRounds, ValidationMetrics metrics) {}

    public enum SearchMode {
        FULL,
        QUICK
    }

    private final List<GridCell> cells;
    private final int cvBlocks;
    private final Path checkpointPath;
    private final SearchMode mode;

    public HyperparameterSearch(List<GridCell> cells, int cvBlocks, Path outputDir) {
        this(cells, cvBlocks, outputDir, SearchMode.FULL);
    }

    public HyperparameterSearch(List<GridCell> cells, int cvBlocks, Path outputDir, SearchMode mode) {
        this.cells = cells;
        this.cvBlocks = cvBlocks;
        this.checkpointPath = outputDir.resolve("hp_search_checkpoint.json");
        this.mode = mode;
    }

    public Result run() {
        LOG.info("Starting hyperparameter search (mode={}, checkpoint={})", mode, checkpointPath);

        Map<String, Double> completed = loadCheckpoint();
        if (!completed.isEmpty()) {
            LOG.info("Resuming: {} combinations already evaluated", completed.size());
        }

        double[] etas;
        int[] depths;
        int[] mcws;
        double[] subsamples;
        double[] colsamples;
        int rounds;

        if (mode == SearchMode.QUICK) {
            etas = new double[]{0.03, 0.05, 0.1};
            depths = new int[]{4, 6, 8};
            mcws = new int[]{5, 10};
            subsamples = new double[]{0.7, 0.8, 0.9};
            colsamples = new double[]{0.7, 0.8, 0.9};
            rounds = 200;
        } else {
            etas = new double[]{0.01, 0.03, 0.05, 0.1};
            depths = new int[]{4, 6, 8};
            mcws = new int[]{5, 10, 20};
            subsamples = new double[]{0.6, 0.7, 0.8, 0.9, 1.0};
            colsamples = new double[]{0.6, 0.7, 0.8, 0.9, 1.0};
            rounds = 500;
        }

        // Phase 1: coarse grid
        Map<String, Object> bestParams = null;
        double bestR2 = Double.NEGATIVE_INFINITY;
        int totalPhase1 = etas.length * depths.length * mcws.length;
        int iter = 0;

        for (double eta : etas) {
            for (int depth : depths) {
                for (int mcw : mcws) {
                    iter++;
                    Map<String, Object> p = XGBoostModelTrainer.defaultParams();
                    p.put("eta", eta);
                    p.put("max_depth", depth);
                    p.put("min_child_weight", mcw);

                    String key = paramKey(p);
                    double r2;

                    if (completed.containsKey(key)) {
                        r2 = completed.get(key);
                        LOG.info("Phase 1 [{}/{}] eta={} depth={} mcw={} → R²={} (cached)",
                                iter, totalPhase1, eta, depth, mcw, fmt(r2));
                    } else {
                        r2 = evaluateParams(p, rounds);
                        completed.put(key, r2);
                        saveCheckpoint(completed);
                        LOG.info("Phase 1 [{}/{}] eta={} depth={} mcw={} → R²={} (best={})",
                                iter, totalPhase1, eta, depth, mcw, fmt(r2), fmt(bestR2));
                    }

                    if (r2 > bestR2) {
                        bestR2 = r2;
                        bestParams = new LinkedHashMap<>(p);
                    }
                }
            }
        }

        LOG.info("Phase 1 best: R²={} params={}", fmt(bestR2), bestParams);

        // Phase 2: refine subsample and colsample_bytree
        int totalPhase2 = subsamples.length * colsamples.length;
        iter = 0;

        for (double ss : subsamples) {
            for (double cs : colsamples) {
                iter++;
                Map<String, Object> p = new LinkedHashMap<>(bestParams);
                p.put("subsample", ss);
                p.put("colsample_bytree", cs);

                String key = paramKey(p);
                double r2;

                if (completed.containsKey(key)) {
                    r2 = completed.get(key);
                    LOG.info("Phase 2 [{}/{}] subsample={} colsample={} → R²={} (cached)",
                            iter, totalPhase2, ss, cs, fmt(r2));
                } else {
                    r2 = evaluateParams(p, rounds);
                    completed.put(key, r2);
                    saveCheckpoint(completed);
                    LOG.info("Phase 2 [{}/{}] subsample={} colsample={} → R²={} (best={})",
                            iter, totalPhase2, ss, cs, fmt(r2), fmt(bestR2));
                }

                if (r2 > bestR2) {
                    bestR2 = r2;
                    bestParams = new LinkedHashMap<>(p);
                }
            }
        }

        LOG.info("Hyperparameter search complete. Best R²={}", fmt(bestR2));
        LOG.info("Best params: {}", bestParams);

        ValidationMetrics finalMetrics = evaluateParamsFull(bestParams, rounds);
        return new Result(bestParams, rounds, finalMetrics);
    }

    private double evaluateParams(Map<String, Object> params, int rounds) {
        try {
            XGBoostModelTrainer trainer = new XGBoostModelTrainer(params, rounds, 30, 0f);
            CrossValidationResult result = new SpatialCrossValidator().runBlockCV(cells, trainer, cvBlocks);
            return result.aggregateMean().r2();
        } catch (Exception e) {
            LOG.warn("Evaluation failed for params {}: {}", params, e.getMessage());
            return Double.NEGATIVE_INFINITY;
        }
    }

    private ValidationMetrics evaluateParamsFull(Map<String, Object> params, int rounds) {
        XGBoostModelTrainer trainer = new XGBoostModelTrainer(params, rounds, 30, 0f);
        CrossValidationResult result = new SpatialCrossValidator().runBlockCV(cells, trainer, cvBlocks);
        return result.aggregateMean();
    }

    private static String paramKey(Map<String, Object> params) {
        return String.format(Locale.ROOT, "eta=%.4f_depth=%s_mcw=%s_ss=%s_cs=%s",
                ((Number) params.get("eta")).doubleValue(),
                params.get("max_depth"),
                params.get("min_child_weight"),
                params.get("subsample"),
                params.get("colsample_bytree"));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    // ── Checkpoint persistence ──────────────────────────────────────────────────

    private Map<String, Double> loadCheckpoint() {
        Map<String, Double> map = new LinkedHashMap<>();
        if (!Files.exists(checkpointPath)) return map;
        try {
            String json = Files.readString(checkpointPath, StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(json);
            JSONArray results = obj.optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    JSONObject entry = results.getJSONObject(i);
                    map.put(entry.getString("key"), entry.getDouble("r2"));
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not load checkpoint: {}", e.getMessage());
        }
        return map;
    }

    private void saveCheckpoint(Map<String, Double> completed) {
        try {
            Files.createDirectories(checkpointPath.getParent());
            JSONObject obj = new JSONObject();
            JSONArray results = new JSONArray();
            for (var entry : completed.entrySet()) {
                JSONObject e = new JSONObject();
                e.put("key", entry.getKey());
                e.put("r2", entry.getValue());
                results.put(e);
            }
            obj.put("results", results);
            obj.put("lastUpdated", java.time.Instant.now().toString());
            obj.put("totalEvaluated", completed.size());
            Files.writeString(checkpointPath, obj.toString(2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Could not save checkpoint: {}", e.getMessage());
        }
    }
}
