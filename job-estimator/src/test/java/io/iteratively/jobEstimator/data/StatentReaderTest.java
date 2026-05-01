package io.iteratively.jobEstimator.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StatentReaderTest {

    private static final String HEADER =
            "RELI;E_KOORD;N_KOORD;B08EMPT;B08EMPT_A;B08EMPT_B;B08EMPT_C;B08EMPT_D;" +
            "B08EMPT_E;B08EMPT_F;B08EMPT_G;B08EMPT_H;B08EMPT_I;B08EMPT_J;B08EMPT_K;" +
            "B08EMPT_L;B08EMPT_M;B08EMPT_N;B08EMPT_O;B08EMPT_P;B08EMPT_Q;B08EMPT_R;" +
            "B08EMPT_S;B08EMPT_T;B08EMPT_U\n";

    @TempDir
    Path tmp;

    private Path writeCsv(String content) throws IOException {
        Path f = tmp.resolve("statent.csv");
        Files.writeString(f, HEADER + content, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void testReadValidRows() throws IOException {
        Path csv = writeCsv(
                "12345678;2600000;1200000;100;10;5;5;5;5;5;5;5;5;5;5;5;5;5;5;5;5;5;5;5;5\n" +
                "12345679;2600250;1200000;50;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;50\n"
        );
        List<StatentRecord> records = new StatentReader().read(csv);

        assertEquals(2, records.size());
        assertEquals(12345678L, records.get(0).reli());
        assertEquals(2600000.0, records.get(0).eKoord());
        assertEquals(1200000.0, records.get(0).nKoord());
        assertEquals(100, records.get(0).totalEmployment());
        assertEquals(21, records.get(0).sectorCounts().length);
        assertEquals(10, records.get(0).sectorCounts()[0]);  // NOGA A
        assertEquals(50, records.get(1).sectorCounts()[20]); // NOGA U
    }

    @Test
    void testSkipsZeroEmploymentRows() throws IOException {
        Path csv = writeCsv(
                "1;2600000;1200000;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0\n" +
                "2;2600250;1200000;42;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;42\n"
        );
        List<StatentRecord> records = new StatentReader().read(csv);

        assertEquals(1, records.size());
        assertEquals(42, records.get(0).totalEmployment());
    }

    @Test
    void testBoundedRead() throws IOException {
        Path csv = writeCsv(
                // inside bbox
                "1;2600000;1200000;10;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;10\n" +
                // outside bbox (easting too large)
                "2;2700000;1200000;10;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;10\n"
        );
        // bbox: E[2580000, 2640000], N[1180000, 1220000]
        List<StatentRecord> records = new StatentReader()
                .readBounded(csv, 2580000, 1180000, 2640000, 1220000);

        assertEquals(1, records.size());
        assertEquals(1L, records.get(0).reli());
    }

    @Test
    void testMissingRequiredColumnThrows() throws IOException {
        Path f = tmp.resolve("bad.csv");
        Files.writeString(f, "WRONG_COL;E_KOORD;N_KOORD\n1;2;3\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> new StatentReader().read(f));
    }

    @Test
    void testCentroidOffset() throws IOException {
        Path csv = writeCsv("1;2600000;1200000;5;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;5\n");
        StatentRecord r = new StatentReader().read(csv).get(0);
        assertEquals(2600050.0, r.centroidE(), 0.001);
        assertEquals(1200050.0, r.centroidN(), 0.001);
    }

    @Test
    void testXValueTreatedAsZero() throws IOException {
        // BFS uses 'X' for suppressed values
        Path csv = writeCsv("1;2600000;1200000;5;X;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;0;5\n");
        StatentRecord r = new StatentReader().read(csv).get(0);
        assertEquals(0, r.sectorCounts()[0]); // suppressed A → 0
    }
}
