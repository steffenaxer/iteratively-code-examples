package io.iteratively.jobEstimator.util;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.geometry.Position2D;
import org.geotools.referencing.CRS;

/** One-shot utility: prints WGS84 city centres as UTM32N coordinates. */
public final class CrsDebug {
    public static void main(String[] args) throws Exception {
        CoordinateReferenceSystem wgs84  = CRS.decode("EPSG:4326",  true);
        CoordinateReferenceSystem utm32n = CRS.decode("EPSG:25832", true);
        MathTransform toUtm = CRS.findMathTransform(wgs84, utm32n, true);

        double[][] points = {
            // name (printed below), lat, lon
            { 52.2524, 10.5398 },  // Braunschweig Hbf
            { 52.1419, 10.5394 },  // Braunschweig Südstadt
            { 52.3143, 10.5069 },  // Braunschweig Norden
            { 52.2336, 10.9671 },  // Helmstedt (check)
        };
        String[] names = {
            "BS-Hbf         ",
            "BS-Südstadt    ",
            "BS-Norden      ",
            "Helmstedt      ",
        };

        System.out.println("\n=== WGS84 → UTM32N (EPSG:25832) ===");
        double minE = Double.MAX_VALUE, maxE = -Double.MAX_VALUE;
        double minN = Double.MAX_VALUE, maxN = -Double.MAX_VALUE;

        for (int i = 0; i < points.length - 1; i++) {
            Position2D src = new Position2D(wgs84, points[i][0], points[i][1]);
            Position2D dst = new Position2D();
            toUtm.transform(src, dst);
            System.out.printf("%s  E=%,.0f  N=%,.0f%n", names[i], dst.x, dst.y);
            minE = Math.min(minE, dst.x); maxE = Math.max(maxE, dst.x);
            minN = Math.min(minN, dst.y); maxN = Math.max(maxN, dst.y);
        }

        // Helmstedt separately
        Position2D helm = new Position2D(wgs84, points[3][0], points[3][1]);
        Position2D helmUtm = new Position2D();
        toUtm.transform(helm, helmUtm);
        System.out.printf("%s  E=%,.0f  N=%,.0f%n", names[3], helmUtm.x, helmUtm.y);

        // Suggest bbox (city extent + 8km buffer)
        double buf = 8000;
        System.out.printf("%n=== Suggested Braunschweig bbox (UTM32N, 8 km buffer) ===%n");
        System.out.printf("minX=%.0f  minY=%.0f  maxX=%.0f  maxY=%.0f%n",
                minE - buf, minN - buf, maxE + buf, maxN + buf);
    }
}
