package chicago.gpsdata;

import org.apache.commons.cli.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.vehicles.Vehicle;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class PseudoGpsEventHandler implements LinkEnterEventHandler, LinkLeaveEventHandler {
    private final double probeRate;
    private final Map<Id<Vehicle>, Boolean> sampledVehicles = new HashMap<>();
    private final Network network;
    private final double samplingInterval;
    private final double noiseSigma;
    private final double pseudoIdInterval;
    private final Random random = new Random();
    private final CSVPrinter printer;
    private final CoordinateTransformation transformation;
    private final Map<Id<Vehicle>, LinkEnterEvent> enterEvents = new HashMap<>();

    public PseudoGpsEventHandler(double probeRate, Network network, Config config, double samplingInterval, double noiseSigma, double pseudoIdInterval, String outputFile) throws IOException {
        this.probeRate = probeRate;
        this.network = network;
        this.samplingInterval = samplingInterval;
        this.noiseSigma = noiseSigma;
        this.pseudoIdInterval = pseudoIdInterval;
        this.transformation = TransformationFactory.getCoordinateTransformation(config.global().getCoordinateSystem(), TransformationFactory.WGS84);
        BufferedWriter writer = IOUtils.getBufferedWriter(outputFile);
        this.printer = new CSVPrinter(writer, CSVFormat.newFormat(',').withRecordSeparator(System.lineSeparator()));
        printer.printRecord("timestamp", "vehicleId", "pseudoId", "lon", "lat");
    }

    public static void main(String[] args) throws IOException, ParseException {
        Options options = new Options();

        options.addOption("p", "probeRate", true, "Sampling probability (e.g. 0.1)");
        options.addOption("s", "samplingInterval", true, "Sampling interval in seconds (e.g. 15.0)");
        options.addOption("n", "noiseSigma", true, "Spatial noise standard deviation (e.g. 10.0)");
        options.addOption("i", "pseudoIdInterval", true, "Pseudo ID interval in seconds (e.g. 1800.0)");
        options.addOption("o", "outputFile", true, "Output CSV file path");
        options.addOption("d", "outputDirectory", true, "MATSim output directory");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        double probeRate = Double.parseDouble(cmd.getOptionValue("probeRate", "0.1"));
        double samplingInterval = Double.parseDouble(cmd.getOptionValue("samplingInterval", "15.0"));
        double noiseSigma = Double.parseDouble(cmd.getOptionValue("noiseSigma", "10.0"));
        double pseudoIdInterval = Double.parseDouble(cmd.getOptionValue("pseudoIdInterval", "1800.0"));
        String outputFile = cmd.getOptionValue("outputFile", "trajectories.csv.gz");
        String outputDirectory = cmd.getOptionValue("outputDirectory");

        SimulationFiles files = findSimulationFilesFromOutput(outputDirectory);
        Network network = NetworkUtils.readNetwork(files.networkFile);
        Config config = files.config();

        EventsManager eventsManager = EventsUtils.createParallelEventsManager();
        PseudoGpsEventHandler pseudoGpsEventHandler = new PseudoGpsEventHandler(
                probeRate, network, config, samplingInterval, noiseSigma, pseudoIdInterval, outputFile
        );

        eventsManager.addHandler(pseudoGpsEventHandler);
        eventsManager.initProcessing();
        MatsimEventsReader matsimEventsReader = new MatsimEventsReader(eventsManager);
        matsimEventsReader.readFile(files.eventsFile());
        eventsManager.finishProcessing();
        pseudoGpsEventHandler.closeWriter();
    }

    public record SimulationFiles(String networkFile, String eventsFile, Config config) {
    }

    public static SimulationFiles findSimulationFilesFromOutput(String outputDirectory) {
        String configPath = outputDirectory + "/output_config.xml";
        Config config = ConfigUtils.loadConfig(configPath);

        String compression = config.controller().getCompressionType().fileEnding; // e.g. "gz", "zstd", or "none"
        String suffix = compression.equals("none") ? ".xml" : ".xml" + compression;

        String networkFile = outputDirectory + "/output_network" + suffix;
        String eventsFile = outputDirectory + "/output_events" + suffix;

        File netFile = new File(networkFile);
        File evtFile = new File(eventsFile);

        if (!netFile.exists() || !evtFile.exists()) {
            throw new RuntimeException("Network or events file not found with expected compression: " + suffix);
        }

        return new SimulationFiles(networkFile, eventsFile, config);
    }


    private boolean isSampled(Id<Vehicle> vehicleId) {
        return sampledVehicles.computeIfAbsent(vehicleId, id -> random.nextDouble() < probeRate);
    }


    @Override
    public void handleEvent(LinkEnterEvent event) {
        if (!isSampled(event.getVehicleId())) return;
        enterEvents.put(event.getVehicleId(), event);
    }


    @Override
    public void handleEvent(LinkLeaveEvent event) {
        Id<Vehicle> vehicleId = event.getVehicleId();
        if (!isSampled(vehicleId)) return;

        LinkEnterEvent enterEvent = enterEvents.get(vehicleId);
        if (enterEvent == null) return;

        double enterTime = enterEvent.getTime();
        double leaveTime = event.getTime();
        double duration = leaveTime - enterTime;

        Link link = network.getLinks().get(event.getLinkId());
        Coord from = network.getNodes().get(link.getFromNode().getId()).getCoord();
        Coord to = network.getNodes().get(link.getToNode().getId()).getCoord();

        for (double t = enterTime; t <= leaveTime; t += samplingInterval) {
            double ratio = (t - enterTime) / duration;
            double x = from.getX() + ratio * (to.getX() - from.getX());
            double y = from.getY() + ratio * (to.getY() - from.getY());

            Coord noisyCoord = transformation.transform(new Coord(applyNoise(x), applyNoise(y)));
            String pseudoId = generatePseudoId(vehicleId, t);
            try {
                printer.printRecord(String.format(Locale.US, "%.1f", t), vehicleId.toString(), pseudoId,
                        String.format(Locale.US, "%.6f", noisyCoord.getX()),
                        String.format(Locale.US, "%.6f", noisyCoord.getY()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        enterEvents.remove(vehicleId);
    }


    private String generatePseudoId(Id<Vehicle> vehicleId, double time) {
        long intervalIndex = (long) (time / pseudoIdInterval);
        return vehicleId.toString() + "_p" + intervalIndex;
    }

    private double applyNoise(double value) {
        return value + random.nextGaussian() * noiseSigma;
    }

    public void closeWriter() {
        try {
            printer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static class TrajectorySegment {
        String vehicleId;
        String pseudoId;
        double startTime;
        double endTime;
        List<Coordinate> coordinates = new ArrayList<>();

        public TrajectorySegment(String vehicleId, String pseudoId, double startTime, double endTime) {
            this.vehicleId = vehicleId;
            this.pseudoId = pseudoId;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        public void addPoint(double lon, double lat) {
            coordinates.add(new Coordinate(lon, lat));
        }

        public String toWKT() {
            GeometryFactory gf = new GeometryFactory();
            LineString line = gf.createLineString(coordinates.toArray(new Coordinate[0]));
            return line.toText();
        }
    }

}
