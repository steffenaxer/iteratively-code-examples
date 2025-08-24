package io.iteratively.matsim;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.io.FileUtils;
import org.geotools.api.referencing.operation.MathTransform;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.contrib.osm.networkReader.SupersonicOsmNetworkReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkSimplifier;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.openstreetmap.osmosis.core.Osmosis;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.matsim.core.network.algorithms.NetworkSimplifier.createNetworkSimplifier;

/**
 * @author steffenaxer
 */
public class NetworkConverter {

    private static final Map<String, MathTransform> transforms = new HashMap<>(); // always to OSM_EPSG
    private static final int DEFAULT_BUFFER = 12000;
    private static final String READ_PBF_FAST = "--read-pbf-fast";
    private static final String BUFFER = "--buffer";

    private static final String CLIP_INCOMPLETE_ENTITIES = "clipIncompleteEntities=true";
    private static final String WRITE_PBF = "--write-pbf";
    private static final String BOUNDING_BOX = "--bounding-box";
    private static final int DEFAULT_WORKERS = Math.max(Runtime.getRuntime().availableProcessors() - 1, 1);
    private static final String WORKERS = "workers=";
    private static final String LOG_PROGRESS = "--log-progress";
    private static final String LEFT = "left=";
    private static final String RIGHT = "right=";
    private static final String TOP = "top=";
    private static final String BOTTOM = "bottom=";

    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.addRequiredOption("u", "url", true, "URL to .osm.pbf file");
        options.addRequiredOption("w", "workdir", true, "Working directory");
        options.addRequiredOption("k", "key", true, "Network key (used for filenames)");
        options.addRequiredOption("e", "epsg", true, "UTM Target EPSG code for coordinate transformation");
        options.addRequiredOption("xmin", "xmin", true, "Bounding box xmin");
        options.addRequiredOption("ymin", "ymin", true, "Bounding box ymin");
        options.addRequiredOption("xmax", "xmax", true, "Bounding box xmax");
        options.addRequiredOption("ymax", "ymax", true, "Bounding box ymax");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        URL url = new URL(cmd.getOptionValue("url"));
        Path workdir = Paths.get(cmd.getOptionValue("workdir"));
        String key = cmd.getOptionValue("key");
        String epsg = cmd.getOptionValue("epsg");
        double xmin = Double.parseDouble(cmd.getOptionValue("xmin"));
        double ymin = Double.parseDouble(cmd.getOptionValue("ymin"));
        double xmax = Double.parseDouble(cmd.getOptionValue("xmax"));
        double ymax = Double.parseDouble(cmd.getOptionValue("ymax"));

        NetworkConverter.createDefaultMATSimNetwork(workdir, url, key, epsg, xmin, ymin, xmax, ymax, TransportMode.drt);
    }

    public static void createDefaultMATSimNetwork(Path workDir, URL osmPbfUrl, String networkKey, String scenarioEPSG, double xmin, double ymin, double xmax, double ymax, String drtMode) {
        String filename = Paths.get(osmPbfUrl.getPath()).getFileName().toString();
        File osmFile = workDir.resolve(filename).toFile();
        downloadUrl(osmPbfUrl, osmFile);
        String bboxPbfFile = workDir.resolve(networkKey + ".bbox.osm.pbf").toString();
        getPbfForBBox(osmFile.toString(), bboxPbfFile, xmin, ymin, xmax, ymax);
        String outputNetworkFilePath = workDir.resolve(networkKey + ".network.xml.gz").toString();
        createDefaultMATSimNetwork(bboxPbfFile, outputNetworkFilePath, scenarioEPSG, drtMode);
    }

    private static void createDefaultMATSimNetwork(String osmPbfFile, String outputNetworkFilePath, String utmEpsg, String drtMode) {
        CoordinateTransformation coordinateTransformation = TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, utmEpsg);
        Network network = new SupersonicOsmNetworkReader.Builder()
                .setCoordinateTransformation(coordinateTransformation)
                .build()
                .read(osmPbfFile);
        NetworkSimplifier networkSimplifier = createNetworkSimplifier(network);
        networkSimplifier.run(network);

        NetworkUtils.cleanNetwork(network, Set.of(TransportMode.car));
        network.getLinks().values().stream()
                .filter(link -> link.getAllowedModes().contains(TransportMode.car))
                .forEach(link -> {
                    Set<String> modes = new HashSet<>(link.getAllowedModes());
                    modes.add(drtMode);
                    link.setAllowedModes(modes);
                });
        new NetworkWriter(network).write(outputNetworkFilePath);
    }

    public static void downloadUrl(URL url, File file) {
        try {
            FileUtils.copyURLToFile(url, file, 5000, 5000);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static File getPbfForBBox(String inputPbfFile, String outputPbfFile, double xmin, double ymin, double xmax, double ymax) {

        List<String> args = List.of(
                READ_PBF_FAST,
                WORKERS + DEFAULT_WORKERS,
                inputPbfFile,
                BUFFER,
                String.valueOf(DEFAULT_BUFFER),
                BOUNDING_BOX,
                LEFT + xmin,
                RIGHT + xmax,
                TOP + ymax,
                BOTTOM + ymin,
                CLIP_INCOMPLETE_ENTITIES,
                LOG_PROGRESS,
                WRITE_PBF, outputPbfFile);

        Osmosis.run(args.toArray(String[]::new));
        return new File(outputPbfFile);
    }
}
