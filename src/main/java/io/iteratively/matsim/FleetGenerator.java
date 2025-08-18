package io.iteratively.matsim;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Identifiable;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetWriter;
import org.matsim.contrib.dvrp.fleet.ImmutableDvrpVehicleSpecification;
import org.matsim.contrib.dvrp.load.IntegerLoadType;
import org.matsim.core.gbl.MatsimRandom;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class FleetGenerator {
	public static Path generateFleet(Network network, int numberOfVehicles, int seats, double serviceEndTime , String outputFile, String drtMode) {
        List<Id<Link>> linkIds = network.getLinks().values().stream().filter(l -> l.getAllowedModes().contains(drtMode)).map(Identifiable::getId).distinct().toList();
		Random random = MatsimRandom.getLocalInstance();

		List<DvrpVehicleSpecification> vehicles = new ArrayList<>();

		for (int i = 0; i < numberOfVehicles; i++) {
			Id<DvrpVehicle> vehicleId = Id.create("drt_" + i, DvrpVehicle.class);
			Id<Link> startLinkId = linkIds.get(random.nextInt(linkIds.size()));

			DvrpVehicleSpecification v = ImmutableDvrpVehicleSpecification.newBuilder()
				.id(Id.create(vehicleId, DvrpVehicle.class)).startLinkId(startLinkId)
				.capacity(seats)
				.serviceBeginTime(0)
				.serviceEndTime(serviceEndTime)
				.build();
			vehicles.add(v);
		}

		File folder = new File(outputFile).getParentFile();
		folder.mkdirs();
		new FleetWriter(vehicles.stream(), new IntegerLoadType("passengers")).write(outputFile);
		return Path.of(outputFile);
	}
}
