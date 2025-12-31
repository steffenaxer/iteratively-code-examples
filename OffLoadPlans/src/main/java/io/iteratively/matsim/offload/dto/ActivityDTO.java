
package io.iteratively.matsim.offload.dto;

public final class ActivityDTO implements PlanElemDTO {
    public String type;
    public String linkId;   // optional
    public double[] coord;  // optional [x,y]
    public Double endTime;  // optional
    public Double startTime;// optional
    public String facilityId; // optional
}
