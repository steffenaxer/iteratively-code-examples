
package io.iteratively.matsim.offload.dto;

public final class GenericRouteDTO {
    public String startLinkId;
    public String endLinkId;
    public Double travelTime;
    public Double distance;
    public String description;
    public String vehicleId;  // Added: vehicle reference ID for the route
}
