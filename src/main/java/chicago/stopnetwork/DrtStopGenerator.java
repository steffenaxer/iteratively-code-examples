package chicago.stopnetwork;

import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.contrib.drt.routing.DrtStopFacility;
import org.matsim.contrib.drt.routing.DrtStopFacilityImpl;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.io.IOException;
import java.util.*;

public class DrtStopGenerator {

    public static void run(Network network, double maxWalkDistance, String outputStops) throws IOException {
        List<DrtStopFacility> allStops = new ArrayList<>();
        for (Link link : network.getLinks().values()) {
            if (!link.getAllowedModes().contains("car")) continue;
            allStops.addAll(createStopsAlongLink(link, maxWalkDistance));
        }

        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;

        for (DrtStopFacility stop : allStops) {
            Coord c = stop.getCoord();
            minX = Math.min(minX, c.getX());
            minY = Math.min(minY, c.getY());
            maxX = Math.max(maxX, c.getX());
            maxY = Math.max(maxY, c.getY());
        }

        QuadTree<DrtStopFacility> stopTree = new QuadTree<>(minX, minY, maxX, maxY);
        List<DrtStopFacility> finalStops = new ArrayList<>();

        for (DrtStopFacility stop : allStops) {
            Collection<DrtStopFacility> nearby = stopTree.getDisk(stop.getCoord().getX(), stop.getCoord().getY(), maxWalkDistance);
            if (nearby.isEmpty()) {
                finalStops.add(stop);
                stopTree.put(stop.getCoord().getX(), stop.getCoord().getY(), stop);
            }
        }

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var f = scenario.getTransitSchedule().getFactory();
        finalStops.forEach(stop -> {
            TransitStopFacility transitStopFacility = f.createTransitStopFacility(Id.create(stop.getId(), TransitStopFacility.class),stop.getCoord(),false);
            transitStopFacility.setLinkId(stop.getLinkId());
            scenario.getTransitSchedule().addStopFacility(transitStopFacility);});
        new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(outputStops);
    }

    private static List<DrtStopFacility> createStopsAlongLink(Link link, double maxWalkDistance) {
        List<DrtStopFacility> stops = new ArrayList<>();
        Coord from = link.getFromNode().getCoord();
        Coord to = link.getToNode().getCoord();
        double length = link.getLength();

        int numStops = Math.max(1, (int) Math.floor(length / maxWalkDistance));
        for (int i = 0; i <= numStops; i++) {
            double ratio = (double) i / numStops;
            double x = from.getX() + ratio * (to.getX() - from.getX());
            double y = from.getY() + ratio * (to.getY() - from.getY());
            Coord stopCoord = new Coord(x, y);
            Id<DrtStopFacility> stopId = Id.create("stop_" + link.getId() + "_" + i, DrtStopFacility.class);
            stops.add(new DrtStopFacilityImpl(stopId, link.getId(), stopCoord, null));
        }

        return stops;
    }
}
