
package io.iteratively.matsim.offload.dto;

import java.util.List;

public final class NetworkRouteDTO {
    public String startLinkId;
    public String endLinkId;
    public List<String> linkIds;
    public Double travelTime;
    public Double distance;
    public String vehicleId;  // Added: vehicle reference ID for the route
}
