package io.iteratively.jobEstimator.grid;

import io.iteratively.jobEstimator.data.StatentRecord;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GridBuilderTest {

    private final GridBuilder builder = new GridBuilder();

    @Test
    void testGridCellCount() {
        // 1000m × 1000m bbox with 250m cells → 4×4 = 16 cells
        Envelope bbox = new Envelope(0, 1000, 0, 1000);
        List<GridCell> cells = builder.buildGrid(bbox, 250);
        assertEquals(16, cells.size());
    }

    @Test
    void testCellBoundsContiguous() {
        Envelope bbox = new Envelope(0, 500, 0, 500);
        List<GridCell> cells = builder.buildGrid(bbox, 250);
        // 2×2 grid
        assertEquals(4, cells.size());
        // All widths and heights should be 250
        for (GridCell c : cells) {
            assertEquals(250.0, c.getBounds().getWidth(),  0.001);
            assertEquals(250.0, c.getBounds().getHeight(), 0.001);
        }
    }

    @Test
    void testCellCenterIsInsideBounds() {
        Envelope bbox = new Envelope(0, 1000, 0, 1000);
        for (GridCell c : builder.buildGrid(bbox, 250)) {
            assertTrue(c.getBounds().contains(c.getCenterX(), c.getCenterY()),
                    "Center must be inside cell bounds for cell " + c.getCellId());
        }
    }

    @Test
    void testAssignStatentLabels_sumAggregation() {
        // One 250m cell covering [0,250]×[0,250]
        // Three STATENT 100m cells inside it (SW corners at 0,0 / 100,0 / 0,100)
        List<GridCell> cells = builder.buildGrid(new Envelope(0, 250, 0, 250), 250);
        assertEquals(1, cells.size());

        List<StatentRecord> records = List.of(
                makeRecord(0, 0, 10),    // centroid at 50,50   → inside
                makeRecord(100, 0, 20),  // centroid at 150,50  → inside
                makeRecord(0, 100, 30)   // centroid at 50,150  → inside
        );

        builder.assignStatentLabels(cells, records);
        assertEquals(60.0, cells.get(0).getLabel(), 0.001);
    }

    @Test
    void testAssignStatentLabels_zeroCells() {
        List<GridCell> cells = builder.buildGrid(new Envelope(0, 250, 0, 250), 250);
        // No STATENT records → label should be 0 (not missing)
        builder.assignStatentLabels(cells, List.of());
        assertTrue(cells.get(0).hasLabel());
        assertEquals(0.0, cells.get(0).getLabel(), 0.001);
    }

    @Test
    void testAssignStatentLabels_outsideCellNotCounted() {
        List<GridCell> cells = builder.buildGrid(new Envelope(0, 250, 0, 250), 250);
        // STATENT cell outside bbox (centroid at 350, 50)
        List<StatentRecord> records = List.of(makeRecord(300, 0, 99));
        builder.assignStatentLabels(cells, records);
        assertEquals(0.0, cells.get(0).getLabel(), 0.001);
    }

    @Test
    void testSwitzerlandGridAlignedTo250m() {
        List<GridCell> cells = builder.buildSwitzerlandGrid(250);
        // Check first cell bounds are multiples of 250
        GridCell first = cells.get(0);
        assertEquals(0, (long) first.getBounds().getMinX() % 250);
        assertEquals(0, (long) first.getBounds().getMinY() % 250);
        assertFalse(cells.isEmpty());
    }

    // Helper: create a StatentRecord with SW corner at (eKoord, nKoord) and given employment
    private StatentRecord makeRecord(double e, double n, int employment) {
        return new StatentRecord(0L, e, n, employment, new int[21]);
    }
}
