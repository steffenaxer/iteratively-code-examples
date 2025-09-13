package chicago.volumes;

import org.apache.commons.cli.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.io.IOUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class VolumeCsvExporter implements LinkEnterEventHandler {
    private static final Logger LOG = LogManager.getLogger(VolumeCsvExporter.class);
    private final Map<Id<Link>, Integer> linkVolumes = new HashMap<>();
    private final CSVPrinter printer;
    private final Network network;

    public VolumeCsvExporter(String outputFile, Network network) throws IOException {
        this.network = network;
        BufferedWriter writer = IOUtils.getBufferedWriter(outputFile);
        this.printer = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("linkId", "volume"));
    }


    @Override
    public void handleEvent(LinkEnterEvent event) {
        linkVolumes.merge(event.getLinkId(), 1, Integer::sum);
    }

    @Override
    public void reset(int iteration) {
        linkVolumes.clear();
    }

    public void writeCsv() {
        int i = 0; try {
            for (var entry : network.getLinks().entrySet()) {
                var value = linkVolumes.getOrDefault(entry.getKey(), 0);
                printer.printRecord(entry.getKey().toString(), value);
                i++;
            }
            printer.close();
        } catch (IOException e) {
            throw new RuntimeException("Error writing CSV", e);
        }

        LOG.info("Exported {} links", i);
    }

    public static void main(String[] args) throws IOException, ParseException {
        Options options = new Options();
        options.addOption("d", "outputDirectory", true, "MATSim output directory");
        options.addOption("o", "outputFile", true, "Volume CSV output file path");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        String outputDirectory = cmd.getOptionValue("outputDirectory");
        String outputFile = cmd.getOptionValue("outputFile", "volumes.csv");

        SimulationFiles files = findSimulationFilesFromOutput(outputDirectory);
        Network network = NetworkUtils.readNetwork(files.networkFile);

        var eventsManager = EventsUtils.createEventsManager();
        var volumeHandler = new VolumeCsvExporter(outputFile, network);
        eventsManager.addHandler(volumeHandler);

        new MatsimEventsReader(eventsManager).readFile(files.eventsFile);
        volumeHandler.writeCsv();
        LOG.info("Volume CSV written to: {}", outputFile);
    }

    public record SimulationFiles(String networkFile, String eventsFile, Config config) {}

    public static SimulationFiles findSimulationFilesFromOutput(String outputDirectory) {
        String configPath = outputDirectory + "/output_config.xml";
        Config config = ConfigUtils.loadConfig(configPath);
        String compression = config.controller().getCompressionType().fileEnding;
        String suffix = compression.equals("none") ? ".xml" : ".xml" + compression;

        String networkFile = outputDirectory + "/output_network" + suffix;
        String eventsFile = outputDirectory + "/output_events" + suffix;

        if (!new File(networkFile).exists() || !new File(eventsFile).exists()) {
            throw new RuntimeException("Network or events file not found with expected compression: " + suffix);
        }

        return new SimulationFiles(networkFile, eventsFile, config);
    }
}
