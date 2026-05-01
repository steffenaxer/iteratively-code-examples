# Job Estimator - Data Basis & Model Target

## What the model predicts

**Target variable: Total number of employed persons (Beschäftigte) per 250 m grid cell.**

The model predicts headcount — the total number of persons employed at business
establishments (Arbeitsstätten) whose registered location falls within a given
250 m × 250 m grid cell. This includes all employees regardless of:

- Economic sector (NOGA A–U)
- Working hours (full-time and part-time)
- Employment status (permanent, temporary, apprentices)
- Nationality or residence

The prediction unit is **persons**, not full-time equivalents (FTE).

## Training data source

| Field          | Value                                                        |
|----------------|--------------------------------------------------------------|
| Dataset        | STATENT 2023 (Statistik der Unternehmensstruktur)            |
| Publisher      | BFS — Bundesamt für Statistik (Swiss Federal Statistical Office) |
| Reference year | 2023                                                         |
| Spatial unit   | Hectare raster (100 m × 100 m cells)                         |
| CRS            | LV95 / EPSG:2056                                             |
| Key column     | `B08EMPT` — Total Beschäftigte                               |
| File           | `STATENT_2023.csv` (semicolon-delimited, UTF-8)              |
| Download       | BFS asset `ag-b-00.03-22-STATENT2023`                        |

### Column semantics

- `RELI` — Unique hectare cell identifier
- `E_KOORD` / `N_KOORD` — South-west corner of the 100 m cell (LV95); centroid = corner + 50 m
- `B08EMPT` — Total employed persons across all sectors
- `B08EMPT_A` … `B08EMPT_U` — Breakdown by NOGA 2008 section (A = agriculture, … U = extraterritorial)

### What "Beschäftigte" means in STATENT

STATENT counts every person who, at the reference date, holds at least one paid
employment relationship at an establishment registered in Switzerland. A person
working at two establishments counts once per establishment (i.e., the same person
can appear in two cells). Self-employed persons with VAT registration are included.
Sole proprietors without VAT registration and marginal employment below the AHV
threshold are **not** included.

Source definition: BFS, "Statistik der Unternehmensstruktur (STATENT) — Methodenbericht", 2023.

## Label aggregation (100 m → 250 m)

The model trains on a 250 m grid (configurable via `-DcellSize`). Each 250 m cell's
label is computed as:

```
label = SUM(B08EMPT) for all 100 m STATENT centroids falling within the 250 m cell bounds
```

A single 250 m cell can contain up to 6.25 STATENT hectare cells (partial overlap is
resolved by centroid containment). Cells with label = 0 are included in training
(important for the two-stage classifier).

## Label transform

The raw label is heavily right-skewed (most cells have few employees, some CBD cells
have thousands). The model is trained on **log1p-transformed** labels:

```
training_label = log(1 + raw_employment)
prediction     = exp(model_output) - 1
```

This stabilises variance and improves fit on the dominant low-employment cells.

## Transfer to other regions

The model is trained exclusively on Swiss data. When applied to other regions
(e.g., Braunschweig / EPSG:25832), the assumption is that the relationship between
physical built environment features (OSM building footprints, land use, road network,
GHSL building height) and employment density generalises across Central European
cities. This assumption has **not been validated** — ground-truth data (e.g., German
Zensus 2022 Erwerbstätige grid or georeferenced Unternehmensregister) would be needed
to assess transfer performance.

## Feature inputs (not labels)

Features are derived purely from openly available geodata:

1. **OSM** — building footprints (area, count, type), road network (length by class),
   land use polygons, POI counts by category
2. **GHSL Built-Up Surface** (optional) — satellite-derived built-up area fraction
3. **GHSL Building Height** (optional) — satellite-derived mean building height per cell
4. **GHSL / WorldPop population** (optional) — gridded population estimates
5. **Spatial context** — distance to nearest highway, density in surrounding cells,
   normalised cell coordinates

See `FeatureVector.java` for the full 37-feature enumeration.
