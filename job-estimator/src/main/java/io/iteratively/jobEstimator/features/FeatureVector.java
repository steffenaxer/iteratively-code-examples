package io.iteratively.jobEstimator.features;

/**
 * Immutable feature vector for a single 250 m grid cell.
 *
 * <p>Contains {@value #FEATURE_COUNT} float features. Use the {@code F_*} constants
 * as indices instead of magic numbers.
 *
 * <p>Note: The {@code float[]} passed to the constructor is copied defensively to
 * preserve immutability.
 */
public record FeatureVector(float[] values) {

    // ── OSM building counts (0–5) ─────────────────────────────────────────────
    public static final int F_BLDG_COMMERCIAL_COUNT  = 0;
    public static final int F_BLDG_INDUSTRIAL_COUNT  = 1;
    public static final int F_BLDG_RETAIL_COUNT      = 2;
    public static final int F_BLDG_OFFICE_COUNT      = 3;
    public static final int F_BLDG_RESIDENTIAL_COUNT = 4;
    public static final int F_BLDG_OTHER_COUNT       = 5;

    // ── OSM POI counts (6–10) ─────────────────────────────────────────────────
    public static final int F_POI_SHOP_COUNT         = 6;
    public static final int F_POI_FOOD_COUNT         = 7;
    public static final int F_POI_HEALTH_COUNT       = 8;
    public static final int F_POI_EDUCATION_COUNT    = 9;
    public static final int F_POI_OFFICE_COUNT       = 10;

    // ── OSM road lengths in meters (11–15) ───────────────────────────────────
    public static final int F_ROAD_MOTORWAY_M        = 11;
    public static final int F_ROAD_PRIMARY_M         = 12;
    public static final int F_ROAD_SECONDARY_M       = 13;
    public static final int F_ROAD_RESIDENTIAL_M     = 14;
    public static final int F_ROAD_SERVICE_M         = 15;

    // ── OSM land use areas in m² (16–19) ─────────────────────────────────────
    public static final int F_LU_COMMERCIAL_M2       = 16;
    public static final int F_LU_INDUSTRIAL_M2       = 17;
    public static final int F_LU_RETAIL_M2           = 18;
    public static final int F_LU_RESIDENTIAL_M2      = 19;

    // ── GHSL (20–21) ─────────────────────────────────────────────────────────
    public static final int F_GHSL_BUILT_SURFACE     = 20; // m² built-up
    public static final int F_GHSL_POP_DENSITY       = 21; // persons/km²

    // ── WorldPop (22) ────────────────────────────────────────────────────────
    public static final int F_WORLDPOP_POP           = 22; // population count

    // ── Copernicus LULC (23–24) ───────────────────────────────────────────────
    public static final int F_COP_URBAN_FRACTION     = 23; // [0, 1]
    public static final int F_COP_AGRI_FRACTION      = 24; // [0, 1]

    // ── Literature-derived (25–31) ─────────────────────────────────────────────
    public static final int F_BLDG_TOTAL_FLOOR_AREA  = 25; // m² (footprint × levels)
    public static final int F_BLDG_FOOTPRINT_COV     = 26; // coverage ratio [0, 1]
    public static final int F_POI_DIVERSITY_SHANNON  = 27; // Shannon entropy of POI types
    public static final int F_TRANSIT_STOP_COUNT     = 28; // bus + tram + rail stops
    public static final int F_DIST_TO_STATION_M      = 29; // distance to nearest rail station
    public static final int F_ROAD_INTERSECTION_CNT  = 30; // road intersections (connectivity)

    // ── Spatial context (31–33) ─────────────────────────────────────────────────
    public static final int F_NEIGHBOR_FLOOR_AREA    = 31; // mean floor area in 3×3 ring
    public static final int F_NEIGHBOR_POI_COUNT     = 32; // mean POI count in 3×3 ring
    public static final int F_NEIGHBOR_TRANSIT       = 33; // mean transit stops in 3×3 ring

    // ── GHSL Building Height (34) ─────────────────────────────────────────────
    public static final int F_GHSL_BUILDING_HEIGHT   = 34; // meters (mean height in cell)

    // ── Spatial coordinates (35–36) ─────────────────────────────────────────────
    public static final int F_CELL_CENTER_X_NORM     = 35; // normalised [0, 1]
    public static final int F_CELL_CENTER_Y_NORM     = 36; // normalised [0, 1]

    /** Total number of features. */
    public static final int FEATURE_COUNT = 37;

    /** Defensive copy constructor. */
    public FeatureVector(float[] values) {
        if (values.length != FEATURE_COUNT) {
            throw new IllegalArgumentException(
                    "Expected " + FEATURE_COUNT + " features, got " + values.length);
        }
        this.values = values.clone();
    }

    /** Returns a copy of the internal array (preserves immutability). */
    @Override
    public float[] values() {
        return values.clone();
    }

    /** Direct read access without copying — use only for read-only XGBoost input. */
    public float[] valuesUnsafe() {
        return values;
    }

    public static String[] featureNames() {
        return new String[]{
            "bldg_commercial_count", "bldg_industrial_count", "bldg_retail_count",
            "bldg_office_count",     "bldg_residential_count", "bldg_other_count",
            "poi_shop_count",        "poi_food_count",         "poi_health_count",
            "poi_education_count",   "poi_office_count",
            "road_motorway_m",       "road_primary_m",         "road_secondary_m",
            "road_residential_m",    "road_service_m",
            "lu_commercial_m2",      "lu_industrial_m2",       "lu_retail_m2",
            "lu_residential_m2",
            "ghsl_built_surface",    "ghsl_pop_density",
            "worldpop_pop",
            "cop_urban_fraction",    "cop_agri_fraction",
            "bldg_total_floor_area", "bldg_footprint_cov",
            "poi_diversity_shannon", "transit_stop_count",
            "dist_to_station_m",     "road_intersection_cnt",
            "neighbor_floor_area",   "neighbor_poi_count",     "neighbor_transit",
            "ghsl_building_height",
            "cell_center_x_norm",    "cell_center_y_norm"
        };
    }
}
