package chicago;

import jakarta.annotation.Nullable;
import org.apache.commons.cli.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;


public class PlansConverter {
    private static final Logger LOG = LogManager.getLogger(PlansConverter.class);
    private static final int PAGE_LIMIT = 50_000;
    private static final String BASE_URL = "https://data.cityofchicago.org/resource/6dvr-xwnh.json";

    public static String buildUrl(String date, int limit, int offset) {
        String whereClause = String.format("trip_start_timestamp >= '%sT00:00:00' AND trip_start_timestamp < '%sT23:59:59'", date, date);
        String encodedWhere = URLEncoder.encode(whereClause, StandardCharsets.UTF_8);

        return String.format("%s?$where=%s&$order=trip_start_timestamp%%20ASC&$limit=%d&$offset=%d", BASE_URL, encodedWhere, limit, offset);
    }

    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.addRequiredOption("t", "token", true, "API token for accessing Chicago TNP data");
        options.addRequiredOption("w", "workdir", true, "Working directory to write plans.xml and cache pages");
        options.addRequiredOption("S", "startDate", true, "Start date in format YYYY-MM-DD");
        options.addOption("E", "endDate", true, "End date in format YYYY-MM-DD (incl. till 24:00:00)");
        options.addRequiredOption("e", "epsg", true, "EPSG code for coordinate system");
        options.addOption("c", "tract", true, "Census tract file. Please download at https://www.census.gov/geo/maps-data/geo.html");
        options.addOption("r", "sampleRate", true, "Rate to sample trips. ");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        String token = cmd.getOptionValue("token");
        Path workdir = Paths.get(cmd.getOptionValue("workdir"));
        String startDateStr = cmd.getOptionValue("startDate");
        String endDateStr = cmd.getOptionValue("endDate");
        String epsg = cmd.getOptionValue("epsg");
        String censusTractFile = cmd.getOptionValue("tract");
        double sampleRate = Double.parseDouble(cmd.getOptionValue("sampleRate","1.0"));

        run(token, workdir, startDateStr, endDateStr, epsg, censusTractFile, sampleRate);
    }

    public static void run(String token, Path workdir, String startDateStr, @Nullable String endDateStr, String epsg, String censusTractFile, double sampleRate) throws IOException, InterruptedException {
        Random random = MatsimRandom.getLocalInstance();
        CoordinateSampler sampler = null;
        if (censusTractFile != null) {
            sampler = new CoordinateSampler(Paths.get(censusTractFile).toFile(), "GEOID");
        }
        CoordinateTransformation transformation = TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, epsg);
        Files.createDirectories(workdir);

        LocalDate startDate = LocalDate.parse(startDateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        LocalDate endDate = endDateStr !=  null ? LocalDate.parse(endDateStr, DateTimeFormatter.ISO_LOCAL_DATE) : null;

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Population population = scenario.getPopulation();

        int daysBetween;
        if (endDate != null) {
            daysBetween = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        } else {
            daysBetween = 1;
        }

        for (int d = 0; d < daysBetween; d++) {
            LocalDate currentDate = startDate.plusDays(d);
            String currentDateStr = currentDate.toString();
            LOG.info("Generating plans for day {}", currentDate);
            int offset = 0;
            int page = 0;

            double dayOffsetSeconds = java.time.Duration.between(startDate.atStartOfDay(), currentDate.atStartOfDay()).toSeconds();

            while (true) {
                Path cacheFile = workdir.resolve("..","..","cache",String.format("day_%s_page_%03d.json", currentDateStr, page));
                String json;
                if (Files.exists(cacheFile)) {
                    json = Files.readString(cacheFile);
                    LOG.info("Loaded cached page: {}", cacheFile);
                } else {
                    String url = buildUrl(currentDateStr, PAGE_LIMIT, offset);
                    json = TripLoader.downloadFromUrl(url, token);
                    cacheFile.toFile().getParentFile().mkdirs();
                    Files.writeString(cacheFile, json);
                    LOG.info("Downloaded and cached page: {}", cacheFile);
                }

                JSONArray trips = new JSONArray(json);
                if (trips.isEmpty()) break;

                Random rand = MatsimRandom.getLocalInstance();
                for (int i = 0; i < trips.length(); i++) {
                    if (random.nextDouble() > sampleRate) {
                        continue;
                    }
                    JSONObject trip = trips.getJSONObject(i);
                    try {
                        String tripId = trip.getString("trip_id");
                        double startLat = trip.getDouble("pickup_centroid_latitude");
                        double startLon = trip.getDouble("pickup_centroid_longitude");
                        double endLat = trip.getDouble("dropoff_centroid_latitude");
                        double endLon = trip.getDouble("dropoff_centroid_longitude");
                        String startTime = trip.getString("trip_start_timestamp");
                        String pickupCensusTract = trip.getString("pickup_census_tract");
                        String dropoffCensusTract = trip.getString("dropoff_census_tract");

                        Person person = population.getFactory().createPerson(Id.createPersonId(tripId));
                        Plan plan = PopulationUtils.createPlan();

                        Coord pickupCoord = (sampler != null) ? sampler.getRandomCoordInTract(pickupCensusTract) : new Coord(startLon, startLat);
                        Activity start = PopulationUtils.createActivityFromCoord("home", getTransformedCoord(pickupCoord, transformation));

                        double parsedEndTime = parseTime(startTime);
                        int mutationRange = 450;
                        int shift = rand.nextInt(2 * mutationRange + 1) - mutationRange;
                        double mutatedEndTime = parsedEndTime + shift;

                        double matsimTime = dayOffsetSeconds + mutatedEndTime;
                        start.setEndTime(matsimTime);
                        plan.addActivity(start);

                        Leg leg = PopulationUtils.createLeg("drt");
                        leg.getAttributes().putAttribute("distance_miles", trip.optDouble("trip_miles", 0.0));
                        leg.getAttributes().putAttribute("fare", trip.optDouble("fare", 0.0));
                        leg.getAttributes().putAttribute("tip", trip.optDouble("tip", 0.0));

                        double tip = trip.optDouble("tip", 0.0);
                        double total = trip.optDouble("trip_total", 0.0);
                        double tip_share = tip / total;

                        leg.getAttributes().putAttribute("trip_start_timestamp", startTime);
                        leg.getAttributes().putAttribute("tip_share", Double.isNaN(tip_share) ? 0.0 : tip_share);
                        leg.getAttributes().putAttribute("additional_charges", trip.optDouble("additional_charges", 0.0));
                        leg.getAttributes().putAttribute("trip_total", trip.optDouble("trip_total", 0.0));
                        leg.getAttributes().putAttribute("trips_pooled", trip.optString("trips_pooled", "0"));
                        leg.getAttributes().putAttribute("shared_trip_match", trip.optBoolean("shared_trip_match", false));
                        leg.getAttributes().putAttribute("shared_trip_authorized", trip.optBoolean("shared_trip_authorized", false));
                        plan.addLeg(leg);

                        Coord dropOffCoord = (sampler != null) ? sampler.getRandomCoordInTract(dropoffCensusTract) : new Coord(endLon, endLat);
                        Activity end = PopulationUtils.createActivityFromCoord("work", getTransformedCoord(dropOffCoord, transformation));
                        plan.addActivity(end);

                        person.addPlan(plan);
                        population.addPerson(person);
                    } catch (Exception e) {
                        LOG.debug("Skipping trip due to missing data: {}", e.getMessage());
                    }
                }

                offset += PAGE_LIMIT;
                page++;
                LOG.debug("Processed {} trips...", offset);
            }
        }

        String plansFile = workdir.resolve("plans.xml.gz").toString();
        new PopulationWriter(population).write(plansFile);
        TripRecordCsvWriter.writeAllPlansToCsvGz(population.getPersons().values(),workdir.resolve("trips.csv.gz").toString());
        LOG.info("Finished writing {}", plansFile);
    }

    static Coord getTransformedCoord(Coord coord, CoordinateTransformation transformation) {
        return transformation.transform(coord);
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
