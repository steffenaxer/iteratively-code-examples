package io.iteratively.jobEstimator.data;

/**
 * One row from the STATENT CSV (BFS Statistik der Unternehmensstruktur).
 *
 * <p>Coordinates are in Swiss LV95 (EPSG:2056). E_KOORD and N_KOORD point to the
 * south-west corner of the 100 m hectare cell; the centroid is at (+50 m, +50 m).
 *
 * <p>NOGA sectors A–U are stored in {@code sectorCounts[0..20]}.
 */
public record StatentRecord(
        long reli,
        double eKoord,
        double nKoord,
        int totalEmployment,
        int[] sectorCounts
) {
    /** Index of NOGA section in {@link #sectorCounts}: 0=A, 1=B, …, 20=U. */
    public static final int SECTOR_COUNT = 21;

    /** LV95 centroid easting of this 100 m cell. */
    public double centroidE() {
        return eKoord + 50.0;
    }

    /** LV95 centroid northing of this 100 m cell. */
    public double centroidN() {
        return nKoord + 50.0;
    }
}
