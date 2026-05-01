package io.iteratively.jobEstimator.data;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the STATENT 2023 CSV file published by the Swiss Federal Statistical Office (BFS).
 *
 * <p>File characteristics: semicolon-delimited, UTF-8, header row present.
 * Key columns: RELI, E_KOORD, N_KOORD, B08EMPT (total), B08EMPT_A through B08EMPT_U.
 *
 * <p>Rows where totalEmployment == 0 are skipped by default (empty raster cells).
 */
public final class StatentReader {

    private static final Logger LOG = LogManager.getLogger(StatentReader.class);

    public static final String COL_RELI   = "RELI";
    public static final String COL_EKOORD = "E_KOORD";
    public static final String COL_NKOORD = "N_KOORD";
    public static final String COL_TOTAL  = "B08EMPT";

    /** NOGA section columns A–U (indices 0–20). */
    public static final String[] SECTOR_COLUMNS = {
        "B08EMPT_A", "B08EMPT_B", "B08EMPT_C", "B08EMPT_D", "B08EMPT_E",
        "B08EMPT_F", "B08EMPT_G", "B08EMPT_H", "B08EMPT_I", "B08EMPT_J",
        "B08EMPT_K", "B08EMPT_L", "B08EMPT_M", "B08EMPT_N", "B08EMPT_O",
        "B08EMPT_P", "B08EMPT_Q", "B08EMPT_R", "B08EMPT_S", "B08EMPT_T",
        "B08EMPT_U"
    };

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setDelimiter(';')
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build();

    /**
     * Reads all STATENT records from {@code csvPath}, skipping rows where
     * {@code B08EMPT == 0}.
     */
    public List<StatentRecord> read(Path csvPath) throws IOException {
        return read(csvPath, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    /**
     * Reads records whose LV95 centroid falls within the given bounding box.
     * Rows with {@code B08EMPT == 0} are always excluded.
     *
     * @param xMin minimum LV95 easting
     * @param yMin minimum LV95 northing
     * @param xMax maximum LV95 easting
     * @param yMax maximum LV95 northing
     */
    public List<StatentRecord> readBounded(Path csvPath,
                                           double xMin, double yMin,
                                           double xMax, double yMax) throws IOException {
        return read(csvPath, xMin, yMin, xMax, yMax);
    }

    private List<StatentRecord> read(Path csvPath,
                                     double xMin, double yMin,
                                     double xMax, double yMax) throws IOException {
        List<StatentRecord> result = new ArrayList<>();
        int skippedZero = 0;
        int skippedBbox = 0;

        try (Reader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
             CSVParser parser = FORMAT.parse(reader)) {

            validateHeaders(parser);

            for (CSVRecord row : parser) {
                long reli     = parseLong(row, COL_RELI);
                double eKoord = parseDouble(row, COL_EKOORD);
                double nKoord = parseDouble(row, COL_NKOORD);
                int total     = parseInt(row, COL_TOTAL);

                if (total == 0) {
                    skippedZero++;
                    continue;
                }

                double centroidE = eKoord + 50.0;
                double centroidN = nKoord + 50.0;
                if (centroidE < xMin || centroidE > xMax || centroidN < yMin || centroidN > yMax) {
                    skippedBbox++;
                    continue;
                }

                int[] sectors = new int[StatentRecord.SECTOR_COUNT];
                for (int i = 0; i < SECTOR_COLUMNS.length; i++) {
                    sectors[i] = parseInt(row, SECTOR_COLUMNS[i]);
                }

                result.add(new StatentRecord(reli, eKoord, nKoord, total, sectors));
            }
        }

        LOG.info("Read {} STATENT records (skipped {} zero-employment, {} outside bbox)",
                result.size(), skippedZero, skippedBbox);
        return result;
    }

    private void validateHeaders(CSVParser parser) {
        java.util.Map<String, Integer> headers = parser.getHeaderMap();
        if (headers == null) {
            throw new IllegalArgumentException("STATENT CSV has no header row");
        }
        for (String required : new String[]{COL_RELI, COL_EKOORD, COL_NKOORD, COL_TOTAL}) {
            if (!headers.containsKey(required)) {
                throw new IllegalArgumentException("STATENT CSV missing required column: " + required);
            }
        }
    }

    private static long parseLong(CSVRecord row, String col) {
        String v = row.get(col).trim();
        return v.isEmpty() ? 0L : Long.parseLong(v);
    }

    private static double parseDouble(CSVRecord row, String col) {
        String v = row.get(col).trim();
        return v.isEmpty() ? 0.0 : Double.parseDouble(v);
    }

    private static int parseInt(CSVRecord row, String col) {
        if (!row.isMapped(col)) return 0;
        String v = row.get(col).trim();
        return (v.isEmpty() || v.equals("X") || v.equals("*")) ? 0 : Integer.parseInt(v);
    }
}
