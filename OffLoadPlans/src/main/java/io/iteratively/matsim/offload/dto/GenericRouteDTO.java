
package io.iteratively.matsim.offload.dto;

public final class GenericRouteDTO {
    public String startLinkId;
    public String endLinkId;
    public Double travelTime;
    public Double distance;
    public String description;
    public String routeType;  // Store the route class name to preserve specialized route types (DrtRoute, TransitPassengerRoute, etc.)
    // Note: GenericRoute does not support vehicleId in MATSim
}
