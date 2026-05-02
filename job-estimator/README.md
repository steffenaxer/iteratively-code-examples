# Job Estimator

Predicts employment density (Beschäftigte per 250m grid cell) using XGBoost,
trained on Swiss STATENT data and transferable to other Central European regions.

See [DATA_BASIS.md](DATA_BASIS.md) for details on the target variable and training data.

## Quick Start

```bash
# 1. Download GHSL raster data (~330 MB)
bash job-estimator/download_ghsl.sh

# 2. Train model on Kanton Zug (fast test, ~2 min)
mvn compile exec:java -pl job-estimator -Dmode=train -Dmodel=twostage \
  -Dbbox=2665000,1209000,2705000,1244000 \
  -DghslBuilt=data/ghsl/GHS_BUILT_S_E2020_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif \
  -DghslHeight=data/ghsl/GHS_BUILT_H_AGBH_E2018_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif \
  -DghslPop=data/ghsl/GHS_POP_E2020_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif

# 3. Apply model to Braunschweig
mvn compile exec:java -pl job-estimator -Dmode=predict \
  -Dbbox=596000,5782000,614000,5799000 -Dcrs=EPSG:25832 \
  -Dosm=data/osm/niedersachsen-latest.osm.pbf \
  -DghslBuilt=data/ghsl/GHS_BUILT_S_E2020_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif \
  -DghslHeight=data/ghsl/GHS_BUILT_H_AGBH_E2018_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif \
  -DghslPop=data/ghsl/GHS_POP_E2020_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif
```

## Modes

| Mode | `-Dmode=` | Description | Output |
|------|-----------|-------------|--------|
| Train | `train` | Train model with spatial block CV | `model.ubj` + CV report |
| Quick Tune | `quicktune` | Fast hyperparameter search + train | Best params + model |
| Full Tune | `tune` | Exhaustive hyperparameter search + train | Best params + model |
| Predict | `predict` | Run inference on target region | GeoJSON + CSV |
| All | `all` | Train + predict in sequence | All outputs |

## Parameters

### Paths (required)

| Parameter | Default | Description |
|-----------|---------|-------------|
| `-Dstatent` | `data/ag-b-00.03-22-STATENT2023/STATENT_2023.csv` | STATENT ground truth CSV |
| `-Dosm` | `data/osm/switzerland-latest.osm.pbf` | OSM PBF file |
| `-Doutput` | `output/job-estimator` | Output directory |

### GHSL Rasters (optional, strongly recommended)

| Parameter | Description |
|-----------|-------------|
| `-DghslBuilt=<path>` | GHS-BUILT-S: built-up surface (m² per pixel) |
| `-DghslHeight=<path>` | GHS-BUILT-H: average building height (metres) |
| `-DghslPop=<path>` | GHS-POP: population count per pixel |

Download all three via `bash job-estimator/download_ghsl.sh`. One tile (R5_C19)
covers both Switzerland and northern Germany.

### Model Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `-Dmodel` | `single` | `single` (one XGBoost regressor) or `twostage` (classifier + regressor) |
| `-Drounds` | `300` | XGBoost boosting rounds |
| `-DcvBlocks` | `5` | Spatial CV blocks per dimension (5 = 25 folds) |
| `-DcellSize` | `250` | Grid cell size in metres |
| `-Dcrs` | `EPSG:2056` | Coordinate reference system |
| `-Dbbox` | full CH | Bounding box: `minX,minY,maxX,maxY` in source CRS |

### Example Bounding Boxes

| Region | CRS | Bbox |
|--------|-----|------|
| Kanton Zug | EPSG:2056 | `2665000,1209000,2705000,1244000` |
| Kanton Zürich | EPSG:2056 | `2669000,1226000,2717000,1283000` |
| Full Switzerland | EPSG:2056 | (default, no bbox needed) |
| Braunschweig | EPSG:25832 | `596000,5782000,614000,5799000` |

## Model Variants

### Single-Stage (`-Dmodel=single`)
One XGBoost regressor on log1p-transformed labels. Simpler, but predicts non-zero
values for empty cells.

### Two-Stage (`-Dmodel=twostage`)
1. **Stage 1**: Binary classifier (employment > 0?)
2. **Stage 2**: Regressor trained only on positive cells (log1p labels)

Prediction: if classifier score > 0.5, apply regressor with expm1 back-transform; else 0.
Generally performs better (R² +5-10%) and produces cleaner spatial patterns.

## Hyperparameter Tuning

Tuning uses coarse-to-fine grid search with spatial block CV:

| Mode | Phase 1 | Phase 2 | Rounds | Est. time (full CH) |
|------|---------|---------|--------|---------------------|
| `quicktune` | 18 combos | 9 combos | 200 | 1.5–3 hours |
| `tune` | 36 combos | 25 combos | 500 | 4–8 hours |

**Resumable**: Progress is checkpointed to `output/job-estimator/hp_search_checkpoint.json`.
If interrupted, restart with the same command — already-evaluated combinations are skipped.

### Full Switzerland Production Run

```bash
mvn compile exec:java -pl job-estimator -Dmode=tune -Dmodel=twostage \
  -DghslBuilt=data/ghsl/GHS_BUILT_S_E2020_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif \
  -DghslHeight=data/ghsl/GHS_BUILT_H_AGBH_E2018_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif \
  -DghslPop=data/ghsl/GHS_POP_E2020_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif
```

## Features (37 total)

### OSM Building Counts (6)
| # | Name | Description |
|---|------|-------------|
| 0 | `bldg_commercial_count` | Commercial buildings in cell |
| 1 | `bldg_industrial_count` | Industrial buildings |
| 2 | `bldg_retail_count` | Retail buildings |
| 3 | `bldg_office_count` | Office buildings |
| 4 | `bldg_residential_count` | Residential buildings |
| 5 | `bldg_other_count` | Other/unclassified buildings |

### OSM POI Counts (5)
| # | Name | Description |
|---|------|-------------|
| 6 | `poi_shop_count` | Shops (supermarket, bakery, etc.) |
| 7 | `poi_food_count` | Restaurants, cafes, bars |
| 8 | `poi_health_count` | Hospitals, pharmacies, doctors |
| 9 | `poi_education_count` | Schools, universities |
| 10 | `poi_office_count` | Offices, banks, insurance |

### OSM Road Network (5)
| # | Name | Description |
|---|------|-------------|
| 11 | `road_motorway_m` | Motorway length in cell (m) |
| 12 | `road_primary_m` | Primary road length |
| 13 | `road_secondary_m` | Secondary road length |
| 14 | `road_residential_m` | Residential road length |
| 15 | `road_service_m` | Service road length |

### OSM Land Use (4)
| # | Name | Description |
|---|------|-------------|
| 16 | `lu_commercial_m2` | Commercial land use area (m²) |
| 17 | `lu_industrial_m2` | Industrial land use area |
| 18 | `lu_retail_m2` | Retail land use area |
| 19 | `lu_residential_m2` | Residential land use area |

### GHSL Satellite Rasters (3)
| # | Name | Description |
|---|------|-------------|
| 20 | `ghsl_built_surface` | Built-up surface area (m²) |
| 21 | `ghsl_pop_density` | Population count in cell |
| 34 | `ghsl_building_height` | Mean building height (m) |

### WorldPop / Copernicus (3)
| # | Name | Description |
|---|------|-------------|
| 22 | `worldpop_pop` | WorldPop population estimate |
| 23 | `cop_urban_fraction` | Copernicus urban land fraction [0,1] |
| 24 | `cop_agri_fraction` | Copernicus agricultural fraction [0,1] |

### Derived / Literature-Based (6)
| # | Name | Description |
|---|------|-------------|
| 25 | `bldg_total_floor_area` | Estimated gross floor area (m²) = footprint × floors |
| 26 | `bldg_footprint_cov` | Building footprint coverage ratio [0,1] |
| 27 | `poi_diversity_shannon` | Shannon entropy of POI type distribution |
| 28 | `transit_stop_count` | Bus + tram + rail stops in cell |
| 29 | `dist_to_station_m` | Distance to nearest rail station (m) |
| 30 | `road_intersection_cnt` | Road intersection count (connectivity proxy) |

### Spatial Context (3)
| # | Name | Description |
|---|------|-------------|
| 31 | `neighbor_floor_area` | Mean floor area in surrounding 3×3 ring |
| 32 | `neighbor_poi_count` | Mean POI count in 3×3 ring |
| 33 | `neighbor_transit` | Mean transit stops in 3×3 ring |

### Coordinates (2)
| # | Name | Description |
|---|------|-------------|
| 35 | `cell_center_x_norm` | Normalised X coordinate [0,1] |
| 36 | `cell_center_y_norm` | Normalised Y coordinate [0,1] |

## Output Files

| File | Description |
|------|-------------|
| `model.ubj` | Trained XGBoost model (single-stage) |
| `model_classifier.ubj` + `model_regressor.ubj` | Two-stage model files |
| `model_twostage.json` | Marker file for two-stage model detection |
| `predictions.geojson` | Training-region predictions (for QC) |
| `predicted_jobs.geojson` | Inference predictions (cells with jobs > 0) |
| `predicted_jobs.csv` | All cells with predicted job count |
| `cv/cv_report.json` | Cross-validation aggregate metrics |
| `cv/cv_metrics.csv` | Per-fold CV metrics |
| `hp_search_checkpoint.json` | Hyperparameter tuning checkpoint (resumable) |

## Performance Benchmarks

| Region | Cells | Time (train) | R² (CV) |
|--------|-------|--------------|---------|
| Kanton Zug | 10K | 2.5 min | 0.536 |
| Kanton Zürich | 44K | 2.8 min | 0.559 |
| Full Switzerland (est.) | 1.3M | ~1.5 hours | — |

All benchmarks with two-stage model + GHSL, default hyperparameters, on a
standard desktop (no GPU required — XGBoost CPU).

## Data Requirements

| Source | Training | Inference | Download |
|--------|----------|-----------|----------|
| STATENT 2023 CSV | Required | Not needed | BFS (restricted) |
| OSM PBF | Required | Required | [Geofabrik](https://download.geofabrik.de/) |
| GHS-BUILT-S | Recommended | Recommended | `download_ghsl.sh` |
| GHS-BUILT-H | Recommended | Recommended | `download_ghsl.sh` |
| GHS-POP | Recommended | Recommended | `download_ghsl.sh` |
