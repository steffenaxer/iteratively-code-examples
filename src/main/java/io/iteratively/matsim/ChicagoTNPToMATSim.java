package io.iteratively.matsim;

import org.json.JSONArray;
import org.json.JSONObject;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.config.ConfigUtils;


public class ChicagoTNPToMATSim {

    public static void main(String[] args) throws Exception {
        String token = "YOUR_TOKEN_HERE";
        int limit = 1000;
        int offset = 0;
        int agentId = 1;

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Population population = scenario.getPopulation();

        while (true) {
            String json = ChicagoTNPDownloader.downloadTrips(token, limit, offset);
            JSONArray trips = new JSONArray(json);
            if (trips.length() == 0) break;

            for (int i = 0; i < trips.length(); i++) {
                JSONObject trip = trips.getJSONObject(i);
                try {
                    double startLat = trip.getDouble("pickup_centroid_latitude");
                    double startLon = trip.getDouble("pickup_centroid_longitude");
                    double endLat = trip.getDouble("dropoff_centroid_latitude");
                    double endLon = trip.getDouble("dropoff_centroid_longitude");
                    String startTime = trip.getString("trip_start_timestamp");

                    Person person = population.getFactory().createPerson(Id.createPersonId("agent_" + agentId++));
                    Plan plan = PopulationUtils.createPlan();

                    Activity start = PopulationUtils.createActivityFromCoord("home", new Coord(startLon, startLat));
                    start.setEndTime(parseTime(startTime));
                    plan.addActivity(start);

                    Leg leg = PopulationUtils.createLeg("drt");
                    plan.addLeg(leg);

                    Activity end = PopulationUtils.createActivityFromCoord("work", new Coord(endLon, endLat));
                    plan.addActivity(end);

                    person.addPlan(plan);
                    population.addPerson(person);

                } catch (Exception e) {
                    System.out.println("Skipping trip due to missing data: " + e.getMessage());
                }
            }

            offset += limit;
            System.out.println("Loaded " + offset + " trips...");
        }

        new PopulationWriter(population).write("output/plans.xml");
        System.out.println("Finished writing plans.xml");
    }

    private static double parseTime(String timestamp) {
        try {
            String[] parts = timestamp.split("T")[1].split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            return hour * 3600 + minute * 60;
        } catch (Exception e) {
            return 8 * 3600; // fallback
        }
    }
}
