#!/usr/bin/env bash
# Downloads GHSL R2023A raster tiles covering Switzerland and Braunschweig.
#
# Products:
#   GHS-BUILT-S  — Built-up surface (m² per pixel), 100m/3ss, WGS84
#   GHS-BUILT-H  — Building height (metres, AGBH), 100m/3ss, WGS84
#   GHS-POP      — Population count per pixel, 100m/3ss, WGS84
#
# Tile coverage:
#   R5_C19  — covers central Europe (~lat 45-55°N, ~lon 5-15°E)
#             includes Switzerland and northern Germany (Braunschweig)
#
# Source: European Commission JRC, GHSL Data Package 2023
#         https://ghsl.jrc.ec.europa.eu/download.php
#
# Usage:  bash job-estimator/download_ghsl.sh
#         (run from project root)

set -euo pipefail

DATA_DIR="data/ghsl"
mkdir -p "$DATA_DIR"

BASE_URL="https://jeodpp.jrc.ec.europa.eu/ftp/jrc-opendata/GHSL"

# ── GHS-BUILT-S (built-up surface, 100m, epoch 2020) ─────────────────���────────
BUILT_S_TIF="GHS_BUILT_S_E2020_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif"
BUILT_S_ZIP="${BUILT_S_TIF%.tif}.zip"
BUILT_S_URL="${BASE_URL}/GHS_BUILT_S_GLOBE_R2023A/GHS_BUILT_S_E2020_GLOBE_R2023A_4326_3ss/V1-0/tiles/${BUILT_S_ZIP}"
if [ ! -f "$DATA_DIR/$BUILT_S_TIF" ]; then
    echo "Downloading GHS-BUILT-S (built-up surface)..."
    curl -L --progress-bar -o "$DATA_DIR/$BUILT_S_ZIP" "$BUILT_S_URL"
    unzip -o "$DATA_DIR/$BUILT_S_ZIP" -d "$DATA_DIR" && rm -f "$DATA_DIR/$BUILT_S_ZIP"
else
    echo "GHS-BUILT-S already exists: $DATA_DIR/$BUILT_S_TIF"
fi

# ── GHS-BUILT-H (building height AGBH, 100m, epoch 2018) ─────────────────��────
BUILT_H_TIF="GHS_BUILT_H_AGBH_E2018_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif"
BUILT_H_ZIP="${BUILT_H_TIF%.tif}.zip"
BUILT_H_URL="${BASE_URL}/GHS_BUILT_H_GLOBE_R2023A/GHS_BUILT_H_AGBH_E2018_GLOBE_R2023A_4326_3ss/V1-0/tiles/${BUILT_H_ZIP}"
if [ ! -f "$DATA_DIR/$BUILT_H_TIF" ]; then
    echo "Downloading GHS-BUILT-H (building height AGBH)..."
    curl -L --progress-bar -o "$DATA_DIR/$BUILT_H_ZIP" "$BUILT_H_URL"
    unzip -o "$DATA_DIR/$BUILT_H_ZIP" -d "$DATA_DIR" && rm -f "$DATA_DIR/$BUILT_H_ZIP"
else
    echo "GHS-BUILT-H already exists: $DATA_DIR/$BUILT_H_TIF"
fi

# ── GHS-POP (population, 100m, epoch 2020) ────────────────────���───────────────
POP_TIF="GHS_POP_E2020_GLOBE_R2023A_4326_3ss_V1_0_R5_C19.tif"
POP_ZIP="${POP_TIF%.tif}.zip"
POP_URL="${BASE_URL}/GHS_POP_GLOBE_R2023A/GHS_POP_E2020_GLOBE_R2023A_4326_3ss/V1-0/tiles/${POP_ZIP}"
if [ ! -f "$DATA_DIR/$POP_TIF" ]; then
    echo "Downloading GHS-POP (population)..."
    curl -L --progress-bar -o "$DATA_DIR/$POP_ZIP" "$POP_URL"
    unzip -o "$DATA_DIR/$POP_ZIP" -d "$DATA_DIR" && rm -f "$DATA_DIR/$POP_ZIP"
else
    echo "GHS-POP already exists: $DATA_DIR/$POP_TIF"
fi

echo ""
echo "Done. Files in $DATA_DIR:"
ls -lh "$DATA_DIR"/*.tif 2>/dev/null || echo "(no .tif files found — check download errors above)"
echo ""
echo "Usage in job-estimator (training with GHSL):"
echo "  mvn compile exec:java -pl job-estimator -Dmode=train -Dmodel=twostage \\"
echo "    -DghslBuilt=$DATA_DIR/$BUILT_S_TIF \\"
echo "    -DghslHeight=$DATA_DIR/$BUILT_H_TIF \\"
echo "    -DghslPop=$DATA_DIR/$POP_TIF"
