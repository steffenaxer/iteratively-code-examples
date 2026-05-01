package io.iteratively.jobEstimator.data;

import crosby.binary.osmosis.OsmosisReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.openstreetmap.osmosis.core.container.v0_6.*;
import org.openstreetmap.osmosis.core.domain.v0_6.*;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;

import java.nio.file.Path;
import java.util.*;

/**
 * Extracts per-cell features from an OSM PBF file.
 *
 * <p>Usage:
 * <ol>
 *   <li>Construct an instance</li>
 *   <li>Call {@link #load(Path)} once to build spatial indexes</li>
 *   <li>Call {@link #queryCell(Envelope)} for each grid cell</li>
 *   <li>Call {@link #close()} when done</li>
 * </ol>
 *
 * <p>All coordinates in WGS84 (longitude/latitude).
 */
public final class OsmFeatureExtractor {

    private static final Logger LOG = LogManager.getLogger(OsmFeatureExtractor.class);

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    /** Feature categories returned by {@link #queryCell(Envelope)}. */
    public enum OsmCategory {
        BUILDING_COMMERCIAL, BUILDING_INDUSTRIAL, BUILDING_RETAIL,
        BUILDING_OFFICE,     BUILDING_RESIDENTIAL, BUILDING_OTHER,
        POI_SHOP,  POI_FOOD,  POI_HEALTH,  POI_EDUCATION,  POI_OFFICE,
        ROAD_MOTORWAY_M,  ROAD_PRIMARY_M,   ROAD_SECONDARY_M,
        ROAD_RESIDENTIAL_M, ROAD_SERVICE_M,
        LANDUSE_COMMERCIAL_M2, LANDUSE_INDUSTRIAL_M2,
        LANDUSE_RETAIL_M2,     LANDUSE_RESIDENTIAL_M2,
        BUILDING_TOTAL_FLOOR_AREA_M2,
        TRANSIT_STOP_COUNT,
        ROAD_INTERSECTION_COUNT
    }

    // Internal containers indexed into STRtrees
    private record TaggedGeometry(Geometry geom, OsmCategory category) {}
    private record BuildingGeometry(Polygon geom, OsmCategory category, int levels) {}

    private STRtree buildingIndex;
    private STRtree buildingAreaIndex; // stores BuildingGeometry with polygon + levels
    private STRtree poiIndex;
    private STRtree roadIndex;
    private STRtree landuseIndex;
    private STRtree transitIndex;
    private STRtree intersectionIndex;
    private final List<Coordinate> stationPositions = new ArrayList<>();

    /**
     * Loads the PBF file and builds spatial indexes.
     * This is memory-intensive (~500 MB for a country-level PBF).
     */
    public void load(Path pbfPath) throws Exception {
        LOG.info("Loading OSM PBF: {}", pbfPath);

        // First pass: collect all node coordinates
        Map<Long, double[]> nodeCoords = new HashMap<>(4_000_000);
        // Second pass structures
        List<TaggedGeometry> buildings = new ArrayList<>();
        List<BuildingGeometry> buildingAreas = new ArrayList<>();
        List<TaggedGeometry> pois      = new ArrayList<>();
        List<TaggedGeometry> roads     = new ArrayList<>();
        List<TaggedGeometry> landuses  = new ArrayList<>();
        List<TaggedGeometry> transitStops = new ArrayList<>();
        Map<Long, Integer> roadNodeRefs = new HashMap<>(); // node ID → road count (for intersections)

        // Pass 1: nodes
        {
            OsmosisReader reader = new OsmosisReader(pbfPath.toFile());
            reader.setSink(new Sink() {
                @Override public void process(EntityContainer ec) {
                    if (ec instanceof NodeContainer nc) {
                        Node n = nc.getEntity();
                        nodeCoords.put(n.getId(), new double[]{n.getLongitude(), n.getLatitude()});
                        // Classify POI nodes
                        OsmCategory poiCat = classifyPoi(n.getTags());
                        if (poiCat != null) {
                            pois.add(new TaggedGeometry(
                                    GF.createPoint(new Coordinate(n.getLongitude(), n.getLatitude())),
                                    poiCat));
                        }
                        // Transit stops
                        TransitType tt = classifyTransit(n.getTags());
                        if (tt != null) {
                            Point p = GF.createPoint(new Coordinate(n.getLongitude(), n.getLatitude()));
                            transitStops.add(new TaggedGeometry(p, OsmCategory.TRANSIT_STOP_COUNT));
                            if (tt == TransitType.STATION) {
                                stationPositions.add(new Coordinate(n.getLongitude(), n.getLatitude()));
                            }
                        }
                    }
                }
                @Override public void initialize(Map<String, Object> m) {}
                @Override public void complete() {}
                @Override public void close() {}
            });
            reader.run();
        }
        LOG.info("Pass 1 complete: {} nodes loaded, {} transit stops, {} stations",
                nodeCoords.size(), transitStops.size(), stationPositions.size());

        // Pass 2: ways
        {
            OsmosisReader reader = new OsmosisReader(pbfPath.toFile());
            reader.setSink(new Sink() {
                @Override public void process(EntityContainer ec) {
                    if (ec instanceof WayContainer wc) {
                        Way way = wc.getEntity();
                        processWay(way, nodeCoords, buildings, buildingAreas,
                                pois, roads, landuses, roadNodeRefs);
                    }
                }
                @Override public void initialize(Map<String, Object> m) {}
                @Override public void complete() {}
                @Override public void close() {}
            });
            reader.run();
        }
        LOG.info("Pass 2 complete: {} buildings, {} POIs, {} roads, {} landuses",
                buildings.size(), pois.size(), roads.size(), landuses.size());

        // Build intersection points from nodes referenced by ≥2 road ways
        List<TaggedGeometry> intersections = new ArrayList<>();
        for (var entry : roadNodeRefs.entrySet()) {
            if (entry.getValue() >= 2) {
                double[] c = nodeCoords.get(entry.getKey());
                if (c != null) {
                    intersections.add(new TaggedGeometry(
                            GF.createPoint(new Coordinate(c[0], c[1])),
                            OsmCategory.ROAD_INTERSECTION_COUNT));
                }
            }
        }
        LOG.info("Derived {} road intersections, {} building polygons with area",
                intersections.size(), buildingAreas.size());

        buildingIndex     = buildIndex(buildings);
        buildingAreaIndex = buildBuildingAreaIndex(buildingAreas);
        poiIndex          = buildIndex(pois);
        roadIndex         = buildIndex(roads);
        landuseIndex      = buildIndex(landuses);
        transitIndex      = buildIndex(transitStops);
        intersectionIndex = buildIndex(intersections);
    }

    /**
     * Returns feature values for the given WGS84 bounding box.
     * Count features are integers stored as doubles; area/length features are in m²/m.
     */
    public Map<OsmCategory, Double> queryCell(Envelope wgs84Bounds) {
        Map<OsmCategory, Double> result = new EnumMap<>(OsmCategory.class);
        for (OsmCategory cat : OsmCategory.values()) result.put(cat, 0.0);

        Geometry cellPoly = envelopeToPolygon(wgs84Bounds);

        // Buildings + POIs: count features inside
        queryCounts(buildingIndex, wgs84Bounds, cellPoly, result, true);
        queryCounts(poiIndex,      wgs84Bounds, cellPoly, result, true);

        // Building floor area: sum footprint_m² × levels
        queryBuildingFloorArea(wgs84Bounds, cellPoly, result);

        // Transit stops and intersections: count inside
        queryCounts(transitIndex,      wgs84Bounds, cellPoly, result, true);
        queryCounts(intersectionIndex,  wgs84Bounds, cellPoly, result, true);

        // Roads: sum clipped lengths in degrees → convert to approximate meters
        queryLengths(roadIndex, wgs84Bounds, cellPoly, result);

        // Land use: sum clipped areas in degrees² → convert to approximate m²
        queryAreas(landuseIndex, wgs84Bounds, cellPoly, result);

        return result;
    }

    /** Returns station positions (WGS84) for nearest-distance computation. */
    public List<Coordinate> getStationPositions() {
        return Collections.unmodifiableList(stationPositions);
    }

    public void close() {
        buildingIndex     = null;
        buildingAreaIndex = null;
        poiIndex          = null;
        roadIndex         = null;
        landuseIndex      = null;
        transitIndex      = null;
        intersectionIndex = null;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void processWay(Way way, Map<Long, double[]> nodeCoords,
                            List<TaggedGeometry> buildings,
                            List<BuildingGeometry> buildingAreas,
                            List<TaggedGeometry> pois,
                            List<TaggedGeometry> roads,
                            List<TaggedGeometry> landuses,
                            Map<Long, Integer> roadNodeRefs) {
        List<WayNode> nodes = way.getWayNodes();
        if (nodes.size() < 2) return;

        Coordinate[] coords = nodes.stream()
                .map(wn -> nodeCoords.get(wn.getNodeId()))
                .filter(Objects::nonNull)
                .map(xy -> new Coordinate(xy[0], xy[1]))
                .toArray(Coordinate[]::new);
        if (coords.length < 2) return;

        Collection<Tag> tags = way.getTags();

        // Buildings (closed ways)
        OsmCategory bldgCat = classifyBuilding(tags);
        if (bldgCat != null && coords.length >= 4 && coords[0].equals2D(coords[coords.length - 1])) {
            try {
                Polygon poly = GF.createPolygon(coords);
                buildings.add(new TaggedGeometry(poly, bldgCat));
                int levels = parseBuildingLevels(tags, bldgCat);
                buildingAreas.add(new BuildingGeometry(poly, bldgCat, levels));
            } catch (Exception e) { LOG.trace("OSM parse skip: {}", e.getMessage()); }
            return;
        }

        // POI from ways (e.g., amenity areas)
        OsmCategory poiCat = classifyPoi(tags);
        if (poiCat != null && coords.length >= 4 && coords[0].equals2D(coords[coords.length - 1])) {
            try {
                Polygon poly = GF.createPolygon(coords);
                pois.add(new TaggedGeometry(poly.getCentroid(), poiCat));
            } catch (Exception e) { LOG.trace("OSM parse skip: {}", e.getMessage()); }
        }

        // Roads — also track node references for intersection detection
        OsmCategory roadCat = classifyRoad(tags);
        if (roadCat != null && coords.length >= 2) {
            try {
                roads.add(new TaggedGeometry(GF.createLineString(coords), roadCat));
                for (WayNode wn : nodes) {
                    roadNodeRefs.merge(wn.getNodeId(), 1, Integer::sum);
                }
            } catch (Exception e) { LOG.trace("OSM parse skip: {}", e.getMessage()); }
        }

        // Land use
        OsmCategory luCat = classifyLanduse(tags);
        if (luCat != null && coords.length >= 4 && coords[0].equals2D(coords[coords.length - 1])) {
            try {
                landuses.add(new TaggedGeometry(GF.createPolygon(coords), luCat));
            } catch (Exception e) { LOG.trace("OSM parse skip: {}", e.getMessage()); }
        }
    }

    private enum TransitType { STOP, STATION }

    private static TransitType classifyTransit(Collection<Tag> tags) {
        for (Tag t : tags) {
            if ("railway".equals(t.getKey())) {
                return switch (t.getValue()) {
                    case "station", "halt" -> TransitType.STATION;
                    case "tram_stop"       -> TransitType.STOP;
                    default                -> null;
                };
            }
            if ("highway".equals(t.getKey()) && "bus_stop".equals(t.getValue())) {
                return TransitType.STOP;
            }
            if ("public_transport".equals(t.getKey()) && "stop_position".equals(t.getValue())) {
                return TransitType.STOP;
            }
        }
        return null;
    }

    private static int parseBuildingLevels(Collection<Tag> tags, OsmCategory type) {
        for (Tag t : tags) {
            if ("building:levels".equals(t.getKey())) {
                try { return Math.max(1, (int) Double.parseDouble(t.getValue())); }
                catch (NumberFormatException ignored) {}
            }
        }
        // Heuristic defaults from literature (Biljecki & Chow 2022)
        return switch (type) {
            case BUILDING_RESIDENTIAL -> 3;
            case BUILDING_OFFICE      -> 4;
            case BUILDING_COMMERCIAL  -> 2;
            case BUILDING_RETAIL      -> 1;
            case BUILDING_INDUSTRIAL  -> 1;
            default                   -> 2;
        };
    }

    private static OsmCategory classifyBuilding(Collection<Tag> tags) {
        for (Tag t : tags) {
            if ("building".equals(t.getKey())) {
                return switch (t.getValue()) {
                    case "commercial", "warehouse", "supermarket" -> OsmCategory.BUILDING_COMMERCIAL;
                    case "industrial", "factory", "manufacture"   -> OsmCategory.BUILDING_INDUSTRIAL;
                    case "retail", "kiosk"                         -> OsmCategory.BUILDING_RETAIL;
                    case "office"                                  -> OsmCategory.BUILDING_OFFICE;
                    case "residential","apartments","house",
                         "detached","semidetached_house","terrace" -> OsmCategory.BUILDING_RESIDENTIAL;
                    case "yes", "no", ""                           -> null;
                    default                                        -> OsmCategory.BUILDING_OTHER;
                };
            }
        }
        return null;
    }

    private static OsmCategory classifyPoi(Collection<Tag> tags) {
        for (Tag t : tags) {
            switch (t.getKey()) {
                case "shop"    -> { return OsmCategory.POI_SHOP; }
                case "office"  -> { return OsmCategory.POI_OFFICE; }
                case "amenity" -> {
                    return switch (t.getValue()) {
                        case "restaurant","cafe","bar","fast_food","pub","food_court"
                                -> OsmCategory.POI_FOOD;
                        case "hospital","clinic","pharmacy","doctors","dentist","veterinary"
                                -> OsmCategory.POI_HEALTH;
                        case "school","university","college","kindergarten","language_school"
                                -> OsmCategory.POI_EDUCATION;
                        case "coworking_space" -> OsmCategory.POI_OFFICE;
                        default -> null;
                    };
                }
            }
        }
        return null;
    }

    private static OsmCategory classifyRoad(Collection<Tag> tags) {
        for (Tag t : tags) {
            if ("highway".equals(t.getKey())) {
                return switch (t.getValue()) {
                    case "motorway","motorway_link"           -> OsmCategory.ROAD_MOTORWAY_M;
                    case "trunk","trunk_link",
                         "primary","primary_link"            -> OsmCategory.ROAD_PRIMARY_M;
                    case "secondary","secondary_link"        -> OsmCategory.ROAD_SECONDARY_M;
                    case "residential","living_street"       -> OsmCategory.ROAD_RESIDENTIAL_M;
                    case "service","unclassified","tertiary","tertiary_link" -> OsmCategory.ROAD_SERVICE_M;
                    default -> null;
                };
            }
        }
        return null;
    }

    private static OsmCategory classifyLanduse(Collection<Tag> tags) {
        for (Tag t : tags) {
            if ("landuse".equals(t.getKey())) {
                return switch (t.getValue()) {
                    case "commercial"            -> OsmCategory.LANDUSE_COMMERCIAL_M2;
                    case "industrial"            -> OsmCategory.LANDUSE_INDUSTRIAL_M2;
                    case "retail"                -> OsmCategory.LANDUSE_RETAIL_M2;
                    case "residential"           -> OsmCategory.LANDUSE_RESIDENTIAL_M2;
                    default -> null;
                };
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void queryCounts(STRtree index, Envelope bounds, Geometry cellPoly,
                              Map<OsmCategory, Double> result, boolean checkContains) {
        if (index == null) return;
        List<TaggedGeometry> candidates = (List<TaggedGeometry>) index.query(bounds);
        for (TaggedGeometry tg : candidates) {
            if (!checkContains || cellPoly.intersects(tg.geom())) {
                result.merge(tg.category(), 1.0, Double::sum);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void queryLengths(STRtree index, Envelope bounds, Geometry cellPoly,
                               Map<OsmCategory, Double> result) {
        if (index == null) return;
        // Average meters per degree at mid-latitude ~47° (Switzerland / Germany)
        double metersPerDegree = 111_000.0 * Math.cos(Math.toRadians(
                (bounds.getMinY() + bounds.getMaxY()) / 2.0));

        List<TaggedGeometry> candidates = (List<TaggedGeometry>) index.query(bounds);
        for (TaggedGeometry tg : candidates) {
            try {
                Geometry clipped = cellPoly.intersection(tg.geom());
                double lengthDeg = clipped.getLength();
                result.merge(tg.category(), lengthDeg * metersPerDegree, Double::sum);
            } catch (Exception e) { LOG.trace("OSM parse skip: {}", e.getMessage()); }
        }
    }

    @SuppressWarnings("unchecked")
    private void queryAreas(STRtree index, Envelope bounds, Geometry cellPoly,
                             Map<OsmCategory, Double> result) {
        if (index == null) return;
        double lat = (bounds.getMinY() + bounds.getMaxY()) / 2.0;
        double metersPerDegLon = 111_000.0 * Math.cos(Math.toRadians(lat));
        double metersPerDegLat = 111_000.0;

        List<TaggedGeometry> candidates = (List<TaggedGeometry>) index.query(bounds);
        for (TaggedGeometry tg : candidates) {
            try {
                Geometry clipped = cellPoly.intersection(tg.geom());
                double areaDeg2 = clipped.getArea();
                double areaM2   = areaDeg2 * metersPerDegLon * metersPerDegLat;
                result.merge(tg.category(), areaM2, Double::sum);
            } catch (Exception e) { LOG.trace("OSM parse skip: {}", e.getMessage()); }
        }
    }

    @SuppressWarnings("unchecked")
    private void queryBuildingFloorArea(Envelope bounds, Geometry cellPoly,
                                        Map<OsmCategory, Double> result) {
        if (buildingAreaIndex == null) return;
        double lat = (bounds.getMinY() + bounds.getMaxY()) / 2.0;
        double metersPerDegLon = 111_000.0 * Math.cos(Math.toRadians(lat));
        double metersPerDegLat = 111_000.0;

        List<BuildingGeometry> candidates = (List<BuildingGeometry>) buildingAreaIndex.query(bounds);
        double totalFloorArea = 0;
        for (BuildingGeometry bg : candidates) {
            if (cellPoly.intersects(bg.geom())) {
                try {
                    Geometry clipped = cellPoly.intersection(bg.geom());
                    double areaDeg2 = clipped.getArea();
                    double footprintM2 = areaDeg2 * metersPerDegLon * metersPerDegLat;
                    totalFloorArea += footprintM2 * bg.levels();
                } catch (Exception e) { LOG.trace("OSM parse skip: {}", e.getMessage()); }
            }
        }
        result.put(OsmCategory.BUILDING_TOTAL_FLOOR_AREA_M2, totalFloorArea);
    }

    private static STRtree buildBuildingAreaIndex(List<BuildingGeometry> items) {
        STRtree tree = new STRtree(16);
        for (BuildingGeometry bg : items) {
            tree.insert(bg.geom().getEnvelopeInternal(), bg);
        }
        if (!items.isEmpty()) tree.build();
        return tree;
    }

    private static STRtree buildIndex(List<TaggedGeometry> items) {
        STRtree tree = new STRtree(16);
        for (TaggedGeometry tg : items) {
            tree.insert(tg.geom().getEnvelopeInternal(), tg);
        }
        if (!items.isEmpty()) tree.build();
        return tree;
    }

    private static Geometry envelopeToPolygon(Envelope env) {
        return GF.createPolygon(new Coordinate[]{
            new Coordinate(env.getMinX(), env.getMinY()),
            new Coordinate(env.getMaxX(), env.getMinY()),
            new Coordinate(env.getMaxX(), env.getMaxY()),
            new Coordinate(env.getMinX(), env.getMaxY()),
            new Coordinate(env.getMinX(), env.getMinY())
        });
    }
}
