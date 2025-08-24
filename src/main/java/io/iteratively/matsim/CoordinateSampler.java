package io.iteratively.matsim;

import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.NameImpl;
import org.locationtech.jts.geom.*;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.utils.gis.GeoFileReader;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class CoordinateSampler {

    private final Map<String, Geometry> tractGeometries = new HashMap<>();
    private final Random random = MatsimRandom.getRandom();

    public CoordinateSampler(File shapefile, String tractIdAttribute) {
        var featureSource = GeoFileReader.readDataFile(shapefile.getAbsolutePath(), new NameImpl(tractIdAttribute));
        try (FeatureIterator<SimpleFeature> features = featureSource.getFeatures().features()) {
            while (features.hasNext()) {
                SimpleFeature feature = features.next();
                String tractId = feature.getAttribute(tractIdAttribute).toString();
                Geometry geometry = (Geometry) feature.getDefaultGeometry();
                tractGeometries.put(tractId, geometry);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Coord getRandomCoordInTract(String tractId) {
        Geometry geometry = tractGeometries.get(tractId);
        if (geometry == null) {
            throw new IllegalArgumentException("Tract ID not found: " + tractId);
        }

        Envelope envelope = geometry.getEnvelopeInternal();
        GeometryFactory factory = geometry.getFactory();

        for (int i = 0; i < 100; i++) { // reduziert auf 100 Versuche
            double x = envelope.getMinX() + random.nextDouble() * envelope.getWidth();
            double y = envelope.getMinY() + random.nextDouble() * envelope.getHeight();
            Point point = factory.createPoint(new Coordinate(x, y));
            if (geometry.contains(point)) {
                return new Coord(x, y);
            }
        }

        throw new RuntimeException("Could not find a random point inside tract geometry: " + tractId);
    }
}
