package io.iteratively.jobEstimator.features;

import io.iteratively.jobEstimator.data.GhslRasterReader;
import io.iteratively.jobEstimator.data.OsmFeatureExtractor;
import io.iteratively.jobEstimator.data.OsmFeatureExtractor.OsmCategory;
import io.iteratively.jobEstimator.data.WorldPopRasterReader;
import io.iteratively.jobEstimator.grid.GridCell;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.geometry.Position2D;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates feature extraction from all data sources for a list of grid cells.
 *
 * <p>All data sources must be pre-loaded (via their respective {@code open()} or {@code load()}
 * methods) before calling {@link #extractAll(List)}.
 *
 * <p>The extractor handles CRS transformation internally: grid cell bounds in the source CRS
 * are transformed to WGS84 before querying OSM / GHSL / WorldPop.
 */
public final class FeatureExtractor {

    private static final Logger LOG = LogManager.getLogger(FeatureExtractor.class);

    private final OsmFeatureExtractor osmExtractor;
    private final GhslRasterReader    ghslBuilt;
    private final GhslRasterReader    ghslPop;
    private final GhslRasterReader    ghslHeight;
    private final WorldPopRasterReader worldPop;
    private final MathTransform        toWgs84;
    private final double normXMin, normXRange;
    private final double normYMin, normYRange;

    /**
     * @param osmExtractor  loaded OSM extractor
     * @param ghslBuilt     opened GHSL built-up surface reader (may be null)
     * @param ghslPop       opened GHSL population reader (may be null)
     * @param ghslHeight    opened GHSL building height reader (may be null)
     * @param worldPop      opened WorldPop reader (may be null)
     * @param sourceCrsCode EPSG code of the grid's native CRS, e.g. "EPSG:2056" or "EPSG:25832"
     * @param normXMin      minimum X of the study area (for coordinate normalisation)
     * @param normXMax      maximum X of the study area
     * @param normYMin      minimum Y of the study area
     * @param normYMax      maximum Y of the study area
     */
    public FeatureExtractor(OsmFeatureExtractor osmExtractor,
                            GhslRasterReader ghslBuilt,
                            GhslRasterReader ghslPop,
                            GhslRasterReader ghslHeight,
                            WorldPopRasterReader worldPop,
                            String sourceCrsCode,
                            double normXMin, double normXMax,
                            double normYMin, double normYMax) {
        this.osmExtractor = osmExtractor;
        this.ghslBuilt    = ghslBuilt;
        this.ghslPop      = ghslPop;
        this.ghslHeight   = ghslHeight;
        this.worldPop     = worldPop;
        this.normXMin     = normXMin;
        this.normXRange   = normXMax - normXMin;
        this.normYMin     = normYMin;
        this.normYRange   = normYMax - normYMin;

        try {
            CoordinateReferenceSystem srcCrs = CRS.decode(sourceCrsCode, true);
            CoordinateReferenceSystem wgs84  = CRS.decode("EPSG:4326",    true);
            this.toWgs84 = CRS.findMathTransform(srcCrs, wgs84, true);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot build transform for CRS: " + sourceCrsCode, e);
        }
    }

    /**
     * Extracts features for a single cell.
     */
    public FeatureVector extractFeatures(GridCell cell) {
        float[] v = new float[FeatureVector.FEATURE_COUNT];

        Envelope wgs84 = transformToWgs84(cell.getBounds());

        // OSM features
        Map<OsmCategory, Double> osm = osmExtractor.queryCell(wgs84);
        v[FeatureVector.F_BLDG_COMMERCIAL_COUNT]  = osm.getOrDefault(OsmCategory.BUILDING_COMMERCIAL,  0.0).floatValue();
        v[FeatureVector.F_BLDG_INDUSTRIAL_COUNT]  = osm.getOrDefault(OsmCategory.BUILDING_INDUSTRIAL,  0.0).floatValue();
        v[FeatureVector.F_BLDG_RETAIL_COUNT]      = osm.getOrDefault(OsmCategory.BUILDING_RETAIL,      0.0).floatValue();
        v[FeatureVector.F_BLDG_OFFICE_COUNT]      = osm.getOrDefault(OsmCategory.BUILDING_OFFICE,      0.0).floatValue();
        v[FeatureVector.F_BLDG_RESIDENTIAL_COUNT] = osm.getOrDefault(OsmCategory.BUILDING_RESIDENTIAL, 0.0).floatValue();
        v[FeatureVector.F_BLDG_OTHER_COUNT]       = osm.getOrDefault(OsmCategory.BUILDING_OTHER,       0.0).floatValue();

        v[FeatureVector.F_POI_SHOP_COUNT]         = osm.getOrDefault(OsmCategory.POI_SHOP,       0.0).floatValue();
        v[FeatureVector.F_POI_FOOD_COUNT]         = osm.getOrDefault(OsmCategory.POI_FOOD,       0.0).floatValue();
        v[FeatureVector.F_POI_HEALTH_COUNT]       = osm.getOrDefault(OsmCategory.POI_HEALTH,     0.0).floatValue();
        v[FeatureVector.F_POI_EDUCATION_COUNT]    = osm.getOrDefault(OsmCategory.POI_EDUCATION,  0.0).floatValue();
        v[FeatureVector.F_POI_OFFICE_COUNT]       = osm.getOrDefault(OsmCategory.POI_OFFICE,     0.0).floatValue();

        v[FeatureVector.F_ROAD_MOTORWAY_M]        = osm.getOrDefault(OsmCategory.ROAD_MOTORWAY_M,    0.0).floatValue();
        v[FeatureVector.F_ROAD_PRIMARY_M]         = osm.getOrDefault(OsmCategory.ROAD_PRIMARY_M,     0.0).floatValue();
        v[FeatureVector.F_ROAD_SECONDARY_M]       = osm.getOrDefault(OsmCategory.ROAD_SECONDARY_M,   0.0).floatValue();
        v[FeatureVector.F_ROAD_RESIDENTIAL_M]     = osm.getOrDefault(OsmCategory.ROAD_RESIDENTIAL_M, 0.0).floatValue();
        v[FeatureVector.F_ROAD_SERVICE_M]         = osm.getOrDefault(OsmCategory.ROAD_SERVICE_M,     0.0).floatValue();

        v[FeatureVector.F_LU_COMMERCIAL_M2]       = osm.getOrDefault(OsmCategory.LANDUSE_COMMERCIAL_M2,  0.0).floatValue();
        v[FeatureVector.F_LU_INDUSTRIAL_M2]       = osm.getOrDefault(OsmCategory.LANDUSE_INDUSTRIAL_M2,  0.0).floatValue();
        v[FeatureVector.F_LU_RETAIL_M2]           = osm.getOrDefault(OsmCategory.LANDUSE_RETAIL_M2,      0.0).floatValue();
        v[FeatureVector.F_LU_RESIDENTIAL_M2]      = osm.getOrDefault(OsmCategory.LANDUSE_RESIDENTIAL_M2, 0.0).floatValue();

        // Raster features (fallback to 0 if not available)
        double builtSurface = ghslBuilt  != null ? ghslBuilt.sumInBounds(wgs84.getMinX(), wgs84.getMinY(), wgs84.getMaxX(), wgs84.getMaxY()) : 0.0;
        double popDensity   = ghslPop    != null ? ghslPop.sumInBounds(  wgs84.getMinX(), wgs84.getMinY(), wgs84.getMaxX(), wgs84.getMaxY()) : 0.0;
        double worldPopVal  = worldPop   != null ? worldPop.sumInBounds( wgs84.getMinX(), wgs84.getMinY(), wgs84.getMaxX(), wgs84.getMaxY()) : 0.0;

        v[FeatureVector.F_GHSL_BUILT_SURFACE] = (float) (Double.isNaN(builtSurface) ? 0.0 : builtSurface);
        v[FeatureVector.F_GHSL_POP_DENSITY]   = (float) (Double.isNaN(popDensity)   ? 0.0 : popDensity);
        v[FeatureVector.F_WORLDPOP_POP]        = (float) (Double.isNaN(worldPopVal)  ? 0.0 : worldPopVal);

        // Copernicus LULC — optional; set to 0 when not available
        v[FeatureVector.F_COP_URBAN_FRACTION] = 0f;
        v[FeatureVector.F_COP_AGRI_FRACTION]  = 0f;

        // ── GHSL Building Height (Biljecki & Chow 2022) ──
        double ghslHeightVal = ghslHeight != null
                ? ghslHeight.meanInBounds(wgs84.getMinX(), wgs84.getMinY(), wgs84.getMaxX(), wgs84.getMaxY())
                : Double.NaN;
        if (Double.isNaN(ghslHeightVal) || ghslHeightVal <= 0) ghslHeightVal = 0;
        v[FeatureVector.F_GHSL_BUILDING_HEIGHT] = (float) ghslHeightVal;

        // ── Literature-derived features (Bock 2023, Mao 2020, Sapena 2022) ──

        // Building floor area: prefer GHSL height (satellite-derived) over OSM heuristics
        double osmFloorArea = osm.getOrDefault(OsmCategory.BUILDING_TOTAL_FLOOR_AREA_M2, 0.0);
        double floorArea;
        if (ghslHeightVal > 0 && builtSurface > 0) {
            double estimatedFloors = ghslHeightVal / 3.0;
            floorArea = builtSurface * estimatedFloors;
        } else {
            floorArea = osmFloorArea;
        }
        v[FeatureVector.F_BLDG_TOTAL_FLOOR_AREA] = (float) floorArea;

        // Building footprint coverage
        double cellAreaM2 = cell.getBounds().getWidth() * cell.getBounds().getHeight();
        double footprintArea = (ghslHeightVal > 0 && builtSurface > 0)
                ? builtSurface
                : (floorArea > 0 ? floorArea / Math.max(1.0, ghslHeightVal > 0 ? ghslHeightVal / 3.0 : 2.5) : 0);
        v[FeatureVector.F_BLDG_FOOTPRINT_COV] = (float) Math.min(1.0, footprintArea / cellAreaM2);

        // POI diversity — Shannon entropy (Sapena 2022, Bock 2023)
        double[] poiCounts = {
                v[FeatureVector.F_POI_SHOP_COUNT],
                v[FeatureVector.F_POI_FOOD_COUNT],
                v[FeatureVector.F_POI_HEALTH_COUNT],
                v[FeatureVector.F_POI_EDUCATION_COUNT],
                v[FeatureVector.F_POI_OFFICE_COUNT]
        };
        v[FeatureVector.F_POI_DIVERSITY_SHANNON] = (float) shannonEntropy(poiCounts);

        // Transit stops
        v[FeatureVector.F_TRANSIT_STOP_COUNT] = osm.getOrDefault(OsmCategory.TRANSIT_STOP_COUNT, 0.0).floatValue();

        // Distance to nearest rail station (computed from cell center in WGS84)
        v[FeatureVector.F_DIST_TO_STATION_M] = (float) distToNearestStation(cell);

        // Road intersection density
        v[FeatureVector.F_ROAD_INTERSECTION_CNT] = osm.getOrDefault(OsmCategory.ROAD_INTERSECTION_COUNT, 0.0).floatValue();

        // Spatial context features — filled in post-processing step
        v[FeatureVector.F_NEIGHBOR_FLOOR_AREA] = 0f;
        v[FeatureVector.F_NEIGHBOR_POI_COUNT]  = 0f;
        v[FeatureVector.F_NEIGHBOR_TRANSIT]    = 0f;

        // Normalised spatial coordinates (indices 35–36)
        v[FeatureVector.F_CELL_CENTER_X_NORM] = normXRange > 0
                ? (float) ((cell.getCenterX() - normXMin) / normXRange) : 0f;
        v[FeatureVector.F_CELL_CENTER_Y_NORM] = normYRange > 0
                ? (float) ((cell.getCenterY() - normYMin) / normYRange) : 0f;

        return new FeatureVector(v);
    }

    /**
     * Batch-extracts features for all cells in parallel using a ForkJoinPool.
     * Updates each cell's features field in-place.
     * After per-cell extraction, computes spatial context features (neighbor aggregation).
     *
     * @return count of cells that received a feature vector with at least one non-zero value
     */
    public int extractAll(List<GridCell> cells) {
        AtomicInteger nonZeroCount = new AtomicInteger(0);
        int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            pool.submit(() -> cells.parallelStream().forEach(cell -> {
                FeatureVector fv = extractFeatures(cell);
                cell.setFeatures(fv);
                if (cell.hasData()) nonZeroCount.incrementAndGet();
            })).get();
        } catch (Exception e) {
            LOG.error("Feature extraction failed", e);
            throw new RuntimeException("Feature extraction failed", e);
        } finally {
            pool.shutdown();
        }

        // Spatial context: mean of key features in 3×3 neighborhood (Mao et al. 2020)
        computeSpatialContext(cells);

        LOG.info("Extracted features for {} cells ({} with non-zero data)", cells.size(), nonZeroCount.get());
        return nonZeroCount.get();
    }

    private void computeSpatialContext(List<GridCell> cells) {
        Map<Long, GridCell> cellMap = new java.util.HashMap<>(cells.size());
        double cellSize = cells.isEmpty() ? 250.0 : cells.get(0).getBounds().getWidth();
        for (GridCell c : cells) {
            long key = gridKey(c.getCenterX(), c.getCenterY(), cellSize);
            cellMap.put(key, c);
        }

        for (GridCell cell : cells) {
            if (!cell.hasFeatures()) continue;
            float[] v = cell.getFeatures().valuesUnsafe();

            double cx = cell.getCenterX();
            double cy = cell.getCenterY();
            float sumFloor = 0, sumPoi = 0, sumTransit = 0;
            int count = 0;

            // 3×3 ring (excluding center)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    long nKey = gridKey(cx + dx * cellSize, cy + dy * cellSize, cellSize);
                    GridCell neighbor = cellMap.get(nKey);
                    if (neighbor != null && neighbor.hasFeatures()) {
                        float[] nv = neighbor.getFeatures().valuesUnsafe();
                        sumFloor   += nv[FeatureVector.F_BLDG_TOTAL_FLOOR_AREA];
                        sumPoi     += nv[FeatureVector.F_POI_SHOP_COUNT] + nv[FeatureVector.F_POI_FOOD_COUNT]
                                    + nv[FeatureVector.F_POI_HEALTH_COUNT] + nv[FeatureVector.F_POI_EDUCATION_COUNT]
                                    + nv[FeatureVector.F_POI_OFFICE_COUNT];
                        sumTransit += nv[FeatureVector.F_TRANSIT_STOP_COUNT];
                        count++;
                    }
                }
            }

            if (count > 0) {
                // Mutate in place — FeatureVector's valuesUnsafe gives direct access
                v[FeatureVector.F_NEIGHBOR_FLOOR_AREA] = sumFloor / count;
                v[FeatureVector.F_NEIGHBOR_POI_COUNT]  = sumPoi / count;
                v[FeatureVector.F_NEIGHBOR_TRANSIT]    = sumTransit / count;
            }
        }
    }

    private static long gridKey(double x, double y, double cellSize) {
        long col = Math.round(x / cellSize);
        long row = Math.round(y / cellSize);
        return col * 1_000_000L + row;
    }

    private static double shannonEntropy(double[] counts) {
        double total = 0;
        for (double c : counts) total += c;
        if (total == 0) return 0;
        double entropy = 0;
        for (double c : counts) {
            if (c > 0) {
                double p = c / total;
                entropy -= p * Math.log(p);
            }
        }
        return entropy;
    }

    private double distToNearestStation(GridCell cell) {
        List<Coordinate> stations = osmExtractor.getStationPositions();
        if (stations.isEmpty()) return 50_000; // large fallback (~50km = no station nearby)

        try {
            double[] center = transformPoint(cell.getCenterX(), cell.getCenterY());
            double minDist = Double.MAX_VALUE;
            for (Coordinate st : stations) {
                double dLon = (center[0] - st.x) * 111_000.0 * Math.cos(Math.toRadians(center[1]));
                double dLat = (center[1] - st.y) * 111_000.0;
                double dist = Math.sqrt(dLon * dLon + dLat * dLat);
                if (dist < minDist) minDist = dist;
            }
            return minDist;
        } catch (Exception e) {
            return 50_000;
        }
    }

    private Envelope transformToWgs84(Envelope srcBounds) {
        try {
            double[] sw = transformPoint(srcBounds.getMinX(), srcBounds.getMinY());
            double[] ne = transformPoint(srcBounds.getMaxX(), srcBounds.getMaxY());
            return new Envelope(
                    Math.min(sw[0], ne[0]), Math.max(sw[0], ne[0]),
                    Math.min(sw[1], ne[1]), Math.max(sw[1], ne[1])
            );
        } catch (Exception e) {
            LOG.warn("CRS transform failed for envelope {}: {}", srcBounds, e.getMessage());
            return srcBounds; // fall back to source coordinates
        }
    }

    private double[] transformPoint(double x, double y) throws Exception {
        Position2D src = new Position2D(x, y);
        Position2D dst = new Position2D();
        toWgs84.transform(src, dst);
        return new double[]{dst.x, dst.y};
    }
}
