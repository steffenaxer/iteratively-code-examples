package io.iteratively.jobEstimator.util;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.geometry.Position2D;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;

class CrsCoordTest {

    @Test
    void printBraunschweigUtm32n() throws Exception {
        CoordinateReferenceSystem wgs84  = CRS.decode("EPSG:4326",  true);
        CoordinateReferenceSystem utm32n = CRS.decode("EPSG:25832", true);
        MathTransform toUtm = CRS.findMathTransform(wgs84, utm32n, true);

        String[] names = {"BS-Hbf    ", "BS-Nord   ", "BS-West   ", "BS-Ost    ", "BS-Sued   ", "Helmstedt "};
        double[] lats  = {52.2524,     52.2861,     52.2612,     52.2505,     52.2280,     52.2336};
        double[] lons  = {10.5398,     10.5269,     10.4920,     10.5930,     10.5365,     10.9671};

        double minE = Double.MAX_VALUE, maxE = -Double.MAX_VALUE;
        double minN = Double.MAX_VALUE, maxN = -Double.MAX_VALUE;

        System.out.println("\n=== Braunschweig + Helmstedt -> UTM32N ===");
        for (int i = 0; i < names.length; i++) {
            var src = new Position2D(wgs84, lons[i], lats[i]); // lon first for EPSG:4326 with xy-order
            var dst = new Position2D();
            toUtm.transform(src, dst);
            System.out.printf("  %s  E=%.0f  N=%.0f%n", names[i], dst.x, dst.y);
            if (i < 5) { // skip Helmstedt for bbox
                minE = Math.min(minE, dst.x);
                maxE = Math.max(maxE, dst.x);
                minN = Math.min(minN, dst.y);
                maxN = Math.max(maxN, dst.y);
            }
        }

        double buf = 5000;
        System.out.printf("%nBbox (5km buffer): minX=%.0f  minY=%.0f  maxX=%.0f  maxY=%.0f%n",
                minE - buf, minN - buf, maxE + buf, maxN + buf);
    }
}
