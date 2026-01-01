
package io.iteratively.matsim.offload.dto;

public final class LegDTO implements PlanElementDTO {
    public static final byte ROUTE_NONE = 0;
    public static final byte ROUTE_NETWORK = 1;
    public static final byte ROUTE_GENERIC = 2;

    public String mode;
    public String routingMode;
    public Double travelTime;
    public byte routeTag;
    public NetworkRouteDTO network;   // if routeTag==NETWORK
    public GenericRouteDTO generic;   // if routeTag==GENERIC
}
