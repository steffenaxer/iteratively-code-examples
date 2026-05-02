package io.iteratively.jobEstimator.grid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.referencing.CRS;
import org.json.JSONArray;
import org.json.JSONObject;
import org.locationtech.jts.geom.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a GeoJSON file and returns the first Polygon/MultiPolygon geometry,
 * transformed from WGS84 (GeoJSON spec) to a target CRS.
 */
public final class GeoJsonRegionReader {

    private static final Logger LOG = LogManager.getLogger(GeoJsonRegionReader.class);
    private static final GeometryFactory GF = new GeometryFactory();

    public record RegionResult(Geometry geometry, Envelope envelope) {}

    /**
     * Reads a GeoJSON file and transforms the geometry to the target CRS.
     *
     * @param geojsonPath path to a GeoJSON file (Feature, FeatureCollection, or bare Geometry)
     * @param targetCrs   target CRS code (e.g. "EPSG:25832")
     * @return geometry in target CRS and its envelope
     */
    public RegionResult read(Path geojsonPath, String targetCrs) throws Exception {
        String content = Files.readString(geojsonPath, StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(content);

        JSONObject geometryJson = extractGeometry(root);
        if (geometryJson == null) {
            throw new IllegalArgumentException("No Polygon or MultiPolygon geometry found in " + geojsonPath);
        }

        Geometry wgs84Geom = parseGeometry(geometryJson);
        LOG.info("Read region from {}: {} with {} points", geojsonPath.getFileName(),
                wgs84Geom.getGeometryType(), wgs84Geom.getNumPoints());

        Geometry projected = transformToTarget(wgs84Geom, targetCrs);
        Envelope env = projected.getEnvelopeInternal();
        LOG.info("Region envelope in {}: [{},{} → {},{}]", targetCrs,
                env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY());

        return new RegionResult(projected, env);
    }

    private JSONObject extractGeometry(JSONObject root) {
        String type = root.getString("type");
        switch (type) {
            case "Feature":
                return root.getJSONObject("geometry");
            case "FeatureCollection":
                JSONArray features = root.getJSONArray("features");
                if (features.isEmpty())
                    throw new IllegalArgumentException("FeatureCollection is empty");
                // Union all geometries in the collection
                if (features.length() == 1) {
                    return features.getJSONObject(0).getJSONObject("geometry");
                }
                return mergedGeometry(features);
            case "Polygon":
            case "MultiPolygon":
                return root;
            default:
                throw new IllegalArgumentException("Unsupported GeoJSON type: " + type);
        }
    }

    private JSONObject mergedGeometry(JSONArray features) {
        // For multi-feature collections, take union of all polygons
        Geometry union = null;
        for (int i = 0; i < features.length(); i++) {
            JSONObject geomJson = features.getJSONObject(i).getJSONObject("geometry");
            Geometry g = parseGeometry(geomJson);
            union = (union == null) ? g : union.union(g);
        }
        // Return as a synthetic MultiPolygon GeoJSON
        JSONObject result = new JSONObject();
        result.put("type", "MultiPolygon");
        result.put("coordinates", toMultiPolygonCoordinates(union));
        return result;
    }

    private JSONArray toMultiPolygonCoordinates(Geometry geom) {
        JSONArray multiCoords = new JSONArray();
        for (int i = 0; i < geom.getNumGeometries(); i++) {
            Polygon p = (Polygon) geom.getGeometryN(i);
            JSONArray polyCoords = new JSONArray();
            polyCoords.put(ringToCoordinates(p.getExteriorRing()));
            for (int h = 0; h < p.getNumInteriorRing(); h++) {
                polyCoords.put(ringToCoordinates(p.getInteriorRingN(h)));
            }
            multiCoords.put(polyCoords);
        }
        return multiCoords;
    }

    private JSONArray ringToCoordinates(LineString ring) {
        JSONArray coords = new JSONArray();
        for (Coordinate c : ring.getCoordinates()) {
            coords.put(new JSONArray().put(c.x).put(c.y));
        }
        return coords;
    }

    private Geometry parseGeometry(JSONObject geomJson) {
        String type = geomJson.getString("type");
        JSONArray coordinates = geomJson.getJSONArray("coordinates");
        return switch (type) {
            case "Polygon" -> parsePolygon(coordinates);
            case "MultiPolygon" -> parseMultiPolygon(coordinates);
            default -> throw new IllegalArgumentException("Unsupported geometry type: " + type);
        };
    }

    private Polygon parsePolygon(JSONArray coords) {
        LinearRing shell = parseRing(coords.getJSONArray(0));
        LinearRing[] holes = new LinearRing[coords.length() - 1];
        for (int i = 1; i < coords.length(); i++) {
            holes[i - 1] = parseRing(coords.getJSONArray(i));
        }
        return GF.createPolygon(shell, holes);
    }

    private MultiPolygon parseMultiPolygon(JSONArray coords) {
        Polygon[] polygons = new Polygon[coords.length()];
        for (int i = 0; i < coords.length(); i++) {
            polygons[i] = parsePolygon(coords.getJSONArray(i));
        }
        return GF.createMultiPolygon(polygons);
    }

    private LinearRing parseRing(JSONArray ring) {
        Coordinate[] cs = new Coordinate[ring.length()];
        for (int i = 0; i < ring.length(); i++) {
            JSONArray point = ring.getJSONArray(i);
            cs[i] = new Coordinate(point.getDouble(0), point.getDouble(1));
        }
        return GF.createLinearRing(cs);
    }

    private Geometry transformToTarget(Geometry wgs84Geom, String targetCrsCode) throws Exception {
        if ("EPSG:4326".equals(targetCrsCode)) {
            return wgs84Geom;
        }
        CoordinateReferenceSystem wgs84 = CRS.decode("EPSG:4326", true);
        CoordinateReferenceSystem target = CRS.decode(targetCrsCode, true);
        MathTransform transform = CRS.findMathTransform(wgs84, target, true);

        Coordinate[] original = wgs84Geom.getCoordinates();
        Coordinate[] transformed = new Coordinate[original.length];
        double[] src = new double[2];
        double[] dst = new double[2];
        for (int i = 0; i < original.length; i++) {
            src[0] = original[i].x;
            src[1] = original[i].y;
            transform.transform(src, 0, dst, 0, 1);
            transformed[i] = new Coordinate(dst[0], dst[1]);
        }

        // Rebuild geometry with transformed coordinates
        if (wgs84Geom instanceof Polygon poly) {
            return rebuildPolygon(poly, transformed);
        } else if (wgs84Geom instanceof MultiPolygon mp) {
            return rebuildMultiPolygon(mp, transformed);
        }
        throw new IllegalStateException("Unexpected geometry type: " + wgs84Geom.getGeometryType());
    }

    private Polygon rebuildPolygon(Polygon original, Coordinate[] allTransformed) {
        int offset = 0;
        LinearRing shell = GF.createLinearRing(
                extractRingCoords(allTransformed, offset, original.getExteriorRing().getNumPoints()));
        offset += original.getExteriorRing().getNumPoints();

        LinearRing[] holes = new LinearRing[original.getNumInteriorRing()];
        for (int i = 0; i < holes.length; i++) {
            int n = original.getInteriorRingN(i).getNumPoints();
            holes[i] = GF.createLinearRing(extractRingCoords(allTransformed, offset, n));
            offset += n;
        }
        return GF.createPolygon(shell, holes);
    }

    private MultiPolygon rebuildMultiPolygon(MultiPolygon original, Coordinate[] allTransformed) {
        Polygon[] polygons = new Polygon[original.getNumGeometries()];
        int offset = 0;
        for (int i = 0; i < polygons.length; i++) {
            Polygon p = (Polygon) original.getGeometryN(i);
            int numCoords = p.getNumPoints();
            Coordinate[] sub = extractRingCoords(allTransformed, offset, numCoords);
            offset += numCoords;

            // Rebuild this polygon from sub-coordinates
            int shellLen = p.getExteriorRing().getNumPoints();
            LinearRing shell = GF.createLinearRing(extractRingCoords(sub, 0, shellLen));
            LinearRing[] holes = new LinearRing[p.getNumInteriorRing()];
            int holeOffset = shellLen;
            for (int h = 0; h < holes.length; h++) {
                int n = p.getInteriorRingN(h).getNumPoints();
                holes[h] = GF.createLinearRing(extractRingCoords(sub, holeOffset, n));
                holeOffset += n;
            }
            polygons[i] = GF.createPolygon(shell, holes);
        }
        return GF.createMultiPolygon(polygons);
    }

    private Coordinate[] extractRingCoords(Coordinate[] all, int offset, int count) {
        Coordinate[] result = new Coordinate[count];
        System.arraycopy(all, offset, result, 0, count);
        return result;
    }
}
