package io.iteratively.jobEstimator.grid;

import io.iteratively.jobEstimator.data.StatentRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a regular grid of {@link GridCell}s over a bounding box and assigns
 * STATENT ground-truth labels by spatial aggregation.
 *
 * <p>Grid cells are aligned to multiples of {@code cellSize} from the origin (0, 0)
 * to ensure reproducibility across different bounding boxes.
 */
public final class GridBuilder {

    private static final Logger LOG = LogManager.getLogger(GridBuilder.class);

    public static final int DEFAULT_CELL_SIZE_M = 250;

    /**
     * Switzerland LV95 bounding box (approximate, snapped to 250 m grid).
     * E: [2480000, 2840000], N: [1070000, 1300000]
     */
    public static final double CH_E_MIN = 2480000;
    public static final double CH_E_MAX = 2840000;
    public static final double CH_N_MIN = 1070000;
    public static final double CH_N_MAX = 1300000;

    /**
     * Builds a regular grid over {@code bounds} with the given cell size (meters).
     * The grid is snapped to multiples of {@code cellSize} from the coordinate origin.
     *
     * @param bounds   bounding box in the native projected CRS
     * @param cellSize cell side length in meters
     */
    public List<GridCell> buildGrid(Envelope bounds, int cellSize) {
        double xMin = Math.floor(bounds.getMinX() / cellSize) * cellSize;
        double yMin = Math.floor(bounds.getMinY() / cellSize) * cellSize;
        double xMax = Math.ceil(bounds.getMaxX()  / cellSize) * cellSize;
        double yMax = Math.ceil(bounds.getMaxY()  / cellSize) * cellSize;

        int nCols = (int) Math.round((xMax - xMin) / cellSize);
        int nRows = (int) Math.round((yMax - yMin) / cellSize);

        List<GridCell> cells = new ArrayList<>(nCols * nRows);
        long id = 0;
        for (int row = 0; row < nRows; row++) {
            for (int col = 0; col < nCols; col++) {
                double cx = xMin + col * cellSize + cellSize / 2.0;
                double cy = yMin + row * cellSize + cellSize / 2.0;
                Envelope cellBounds = new Envelope(
                        xMin + col * cellSize,
                        xMin + (col + 1) * cellSize,
                        yMin + row * cellSize,
                        yMin + (row + 1) * cellSize
                );
                cells.add(new GridCell(id++, cx, cy, cellBounds));
            }
        }
        LOG.info("Built grid: {}×{} = {} cells (cellSize={}m, bbox={})",
                nCols, nRows, cells.size(), cellSize, bounds);
        return cells;
    }

    /**
     * Builds a grid over the envelope of the given geometry, then filters to only
     * include cells whose center falls within the geometry.
     *
     * @param regionGeometry polygon/multipolygon in the target CRS
     * @param cellSize       cell side length in meters
     */
    public List<GridCell> buildGrid(Geometry regionGeometry, int cellSize) {
        Envelope env = regionGeometry.getEnvelopeInternal();
        List<GridCell> allCells = buildGrid(env, cellSize);

        PreparedGeometry prepared = PreparedGeometryFactory.prepare(regionGeometry);
        GeometryFactory gf = new GeometryFactory();

        List<GridCell> filtered = new ArrayList<>();
        long id = 0;
        for (GridCell cell : allCells) {
            Point center = gf.createPoint(new Coordinate(cell.getCenterX(), cell.getCenterY()));
            if (prepared.contains(center)) {
                filtered.add(new GridCell(id++, cell.getCenterX(), cell.getCenterY(), cell.getBounds()));
            }
        }
        LOG.info("Region filter: {} → {} cells ({}% inside polygon)",
                allCells.size(), filtered.size(),
                allCells.isEmpty() ? 0 : (100 * filtered.size() / allCells.size()));
        return filtered;
    }

    /**
     * Convenience method: builds the full Switzerland grid at the default cell size.
     */
    public List<GridCell> buildSwitzerlandGrid(int cellSize) {
        return buildGrid(new Envelope(CH_E_MIN, CH_E_MAX, CH_N_MIN, CH_N_MAX), cellSize);
    }

    /**
     * Assigns STATENT labels to grid cells.
     *
     * <p>For each grid cell the label is the <b>sum</b> of {@code totalEmployment}
     * across all STATENT 100 m cell centroids that fall within the cell bounds.
     * Cells with no overlapping STATENT records receive label = 0.
     *
     * @param cells   grid cells to label (modified in-place)
     * @param records STATENT records (employment data)
     */
    public void assignStatentLabels(List<GridCell> cells, List<StatentRecord> records) {
        // Build STRtree over STATENT centroids for fast spatial lookup
        STRtree index = new STRtree();
        for (StatentRecord r : records) {
            Coordinate centroid = new Coordinate(r.centroidE(), r.centroidN());
            index.insert(new Envelope(centroid), r);
        }
        index.build();

        int labeled = 0;
        for (GridCell cell : cells) {
            Envelope bounds = cell.getBounds();
            @SuppressWarnings("unchecked")
            List<StatentRecord> candidates = (List<StatentRecord>) index.query(bounds);

            double totalJobs = 0;
            for (StatentRecord candidate : candidates) {
                double e = candidate.centroidE();
                double n = candidate.centroidN();
                if (bounds.contains(e, n)) {
                    totalJobs += candidate.totalEmployment();
                }
            }
            cell.setLabel(totalJobs);
            if (totalJobs > 0) labeled++;
        }
        LOG.info("Assigned labels to {} cells ({} with employment > 0)", cells.size(), labeled);
    }
}
