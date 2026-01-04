package io.iteratively.matsim.offload;

import io.iteratively.matsim.offload.dto.*;
import org.apache.fury.Fury;
import org.apache.fury.ThreadSafeFury;
import org.apache.fury.config.Language;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.vehicles.Vehicle;

import java.util.ArrayList;

public final class FuryPlanCodec {
    private final PopulationFactory factory;
    private final ThreadSafeFury fury;

    public FuryPlanCodec(PopulationFactory factory) {
        this.factory = factory;
        this.fury = Fury.builder()
                .withLanguage(Language.JAVA)
                .requireClassRegistration(true)
                .buildThreadSafeFury();
        fury.register(PlanDTO.class);
        fury.register(PlanElementDTO.class);
        fury.register(ActivityDTO.class);
        fury.register(LegDTO.class);
        fury.register(NetworkRouteDTO.class);
        fury.register(GenericRouteDTO.class);
        fury.register(ArrayList.class);
        fury.register(String.class);
        fury.register(double[].class);
        fury.register(Double.class);
        fury.register(Integer.class);
    }

    public byte[] serialize(Plan plan) {
        PlanDTO dto = toDTO(plan);
        return fury.serialize(dto);
    }

    public Plan deserialize(byte[] bytes) {
        PlanDTO dto = (PlanDTO) fury.deserialize(bytes);
        return fromDTO(dto);
    }

    private PlanDTO toDTO(Plan plan) {
        var out = new PlanDTO();
        out.elements = new ArrayList<>();
        out.planMutator = plan.getPlanMutator();
        for (var pe : plan.getPlanElements()) {
            if (pe instanceof Activity a) {
                var d = new ActivityDTO();
                d.type = a.getType();
                d.linkId = a.getLinkId() == null ? null : a.getLinkId().toString();
                if (a.getCoord() != null) d.coord = new double[]{a.getCoord().getX(), a.getCoord().getY()};
                d.endTime = toNullable(a.getEndTime());
                d.startTime = toNullable(a.getStartTime());
                d.facilityId = a.getFacilityId() == null ? null : a.getFacilityId().toString();
                out.elements.add(d);
            } else if (pe instanceof Leg l) {
                var d = new LegDTO();
                d.mode = l.getMode();
                d.routingMode = l.getRoutingMode();
                d.travelTime = toNullable(l.getTravelTime());
                d.departureTime = toNullable(l.getDepartureTime());  // Added: serialize departureTime
                if (l.getRoute() == null) {
                    d.routeTag = LegDTO.ROUTE_NONE;
                } else if (l.getRoute() instanceof NetworkRoute nr) {
                    d.routeTag = LegDTO.ROUTE_NETWORK;
                    var r = new NetworkRouteDTO();
                    r.startLinkId = nr.getStartLinkId().toString();
                    r.endLinkId = nr.getEndLinkId().toString();
                    var ids = nr.getLinkIds();
                    r.linkIds = new ArrayList<>(ids.size());
                    for (Id<Link> id : ids) r.linkIds.add(id.toString());
                    r.travelTime = toNullable(nr.getTravelTime());
                    r.distance = nr.getDistance();
                    r.vehicleId = nr.getVehicleId() == null ? null : nr.getVehicleId().toString();  // Added: serialize vehicleId
                    d.network = r;
                } else {
                    d.routeTag = LegDTO.ROUTE_GENERIC;
                    var gr = new GenericRouteDTO();
                    gr.startLinkId = l.getRoute().getStartLinkId() == null ? null : l.getRoute().getStartLinkId().toString();
                    gr.endLinkId = l.getRoute().getEndLinkId() == null ? null : l.getRoute().getEndLinkId().toString();
                    gr.travelTime = l.getRoute().getTravelTime() == null ? null : l.getRoute().getTravelTime().seconds();
                    gr.distance = l.getRoute().getDistance();
                    gr.description = l.getRoute().getRouteDescription();
                    // Note: GenericRoute does not support vehicleId in MATSim
                    d.generic = gr;
                }
                out.elements.add(d);
            } else {
                throw new IllegalStateException("Unexpected PlanElement " + pe.getClass());
            }
        }
        return out;
    }

    private Plan fromDTO(PlanDTO dto) {
        var plan = factory.createPlan();
        if (dto.planMutator != null) {
            plan.setPlanMutator(dto.planMutator);
        }
        for (var e : dto.elements) {
            if (e instanceof ActivityDTO d) {
                Activity a = (d.linkId != null)
                        ? factory.createActivityFromLinkId(d.type, Id.createLinkId(d.linkId))
                        : factory.createActivityFromCoord(d.type,
                        d.coord == null ? null : new Coord(d.coord[0], d.coord[1]));
                if (d.endTime != null) a.setEndTime(d.endTime);
                if (d.startTime != null) a.setStartTime(d.startTime);
                if (d.facilityId != null)
                    a.setFacilityId(Id.create(d.facilityId, org.matsim.facilities.ActivityFacility.class));
                plan.addActivity(a);
            } else if (e instanceof LegDTO d) {
                Leg l = factory.createLeg(d.mode);
                if (d.travelTime != null) l.setTravelTime(d.travelTime);
                if (d.departureTime != null) l.setDepartureTime(d.departureTime);  // Added: deserialize departureTime
                if (d.routingMode != null) l.setRoutingMode(d.routingMode);
                if (d.routeTag == LegDTO.ROUTE_NETWORK && d.network != null) {
                    var nr = RouteUtils.createLinkNetworkRouteImpl(
                            Id.createLinkId(d.network.startLinkId), Id.createLinkId(d.network.endLinkId));
                    var mid = new ArrayList<Id<Link>>(d.network.linkIds.size());
                    for (String s : d.network.linkIds) mid.add(Id.createLinkId(s));
                    nr.setLinkIds(nr.getStartLinkId(), mid, nr.getEndLinkId());
                    if (d.network.travelTime != null) nr.setTravelTime(d.network.travelTime);
                    if (d.network.distance != null) nr.setDistance(d.network.distance);
                    if (d.network.vehicleId != null) nr.setVehicleId(Id.createVehicleId(d.network.vehicleId));  // Added: deserialize vehicleId
                    l.setRoute(nr);
                } else if (d.routeTag == LegDTO.ROUTE_GENERIC && d.generic != null) {
                    var startLink = d.generic.startLinkId != null ? Id.createLinkId(d.generic.startLinkId) : null;
                    var endLink = d.generic.endLinkId != null ? Id.createLinkId(d.generic.endLinkId) : null;
                    var gr = RouteUtils.createGenericRouteImpl(startLink, endLink);
                    if (d.generic.travelTime != null) gr.setTravelTime(d.generic.travelTime);
                    if (d.generic.distance != null) gr.setDistance(d.generic.distance);
                    gr.setRouteDescription(d.generic.description);
                    // Note: GenericRoute does not support vehicleId in MATSim
                    l.setRoute(gr);
                }
                plan.addLeg(l);
            } else {
                throw new IllegalStateException("Unexpected DTO element " + e.getClass());
            }
        }
        return plan;
    }

    private static Double toNullable(OptionalTime t) {
        if (t.isUndefined()) {
            return null;
        }
        return t.seconds();
    }
}