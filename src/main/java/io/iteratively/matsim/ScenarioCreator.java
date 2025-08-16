package io.iteratively.matsim;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.optimizer.insertion.parallel.DrtParallelInserterParams;
import org.matsim.contrib.drt.optimizer.insertion.repeatedselective.RepeatedSelectiveInsertionSearchParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.zone.skims.DvrpTravelTimeMatrixParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.NetworkUtils;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * @author steffenaxer
 */
public class ScenarioCreator {
    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.addRequiredOption("w", "workdir", true, "Working directory for scenario");
        options.addRequiredOption("u", "url", true, "URL to .osm.pbf file");
        options.addRequiredOption("k", "key", true, "Network key");
        options.addRequiredOption("e", "epsg", true, "EPSG code");
        options.addRequiredOption("d", "date", true, "Date for TNP data (YYYY-MM-DD)");
        options.addRequiredOption("t", "token", true, "API token for Chicago TNP");
        options.addRequiredOption("bbox", "bbox", true, "Bounding box as xmin,ymin,xmax,ymax");
        options.addOption("c", "tract", true, "Census tract file. Please download at https://www.census.gov/geo/maps-data/geo.html");


        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        Path workDir = Paths.get(cmd.getOptionValue("workdir"));
        String networkKey = cmd.getOptionValue("key");
        String epsg = cmd.getOptionValue("epsg");
        String date = cmd.getOptionValue("date");
        String token = cmd.getOptionValue("token");
        String tract = cmd.getOptionValue("tract");
        URL osmUrl = new URL(cmd.getOptionValue("url"));

        Path networkDir = workDir.resolve("network");
        Path plansDir = workDir.resolve("plans");
        Path fleetDir = workDir.resolve("fleet");
        Path outputDir = workDir.resolve("output");

        String[] bboxParts = cmd.getOptionValue("bbox").split(",");
        if (bboxParts.length != 4) {
            throw new IllegalArgumentException("Bounding box must have 4 comma-separated values: xmin,ymin,xmax,ymax");
        }
        double xmin = Double.parseDouble(bboxParts[0]);
        double ymin = Double.parseDouble(bboxParts[1]);
        double xmax = Double.parseDouble(bboxParts[2]);
        double ymax = Double.parseDouble(bboxParts[3]);


        Files.createDirectories(networkDir);
        Files.createDirectories(plansDir);
        Files.createDirectories(fleetDir);
        Files.createDirectories(outputDir);

        NetworkConverter.createDefaultMATSimNetwork(networkDir, osmUrl, networkKey, epsg, xmin, ymin, xmax, ymax, TransportMode.drt);
        PlansConverter.run(token, plansDir, date, epsg, tract);

        String networkFile = networkDir.resolve(networkKey + ".network.xml.gz").toString();
        String plansFile = plansDir.resolve("plans.xml.gz").toString();
        String fleetFile = fleetDir.resolve("fleet.xml.gz").toString();
        Config config = prepareConfig(networkFile, 2000, 24 * 3600, 2, fleetFile);
        config.network().setInputFile(networkFile);
        config.plans().setInputFile(plansFile);
        config.controller().setOutputDirectory(outputDir.toString());
        ConfigUtils.writeConfig(config, String.valueOf(workDir.resolve("config.xml")));
    }

    public static Config prepareConfig(String networkFile, int numberOfVehicles, double endTime, int iterations, String fleetFile) {
        Config config = ConfigUtils.createConfig();

        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(iterations);

        DvrpConfigGroup dvrpConfigGroup = new DvrpConfigGroup();
        dvrpConfigGroup.setNetworkModes(Set.of(TransportMode.car, TransportMode.drt));

        // Resolution to estimate travel times
        DvrpTravelTimeMatrixParams dvrpTravelTimeMatrixParams = new DvrpTravelTimeMatrixParams();
        dvrpTravelTimeMatrixParams.setMaxNeighborDistance(0);
        SquareGridZoneSystemParams dvrpZones = new SquareGridZoneSystemParams();
        dvrpZones.setCellSize(1000);
        dvrpTravelTimeMatrixParams.addParameterSet(dvrpZones);
        dvrpConfigGroup.addParameterSet(dvrpTravelTimeMatrixParams);
        config.addModule(dvrpConfigGroup);


        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.global().setNumberOfThreads(8);
        config.qsim().setEndTime(endTime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);

        Network network = NetworkUtils.readNetwork(networkFile);
        Path fleet = FleetGenerator.generateFleet(network, numberOfVehicles, 6, endTime, fleetFile, TransportMode.drt);

        MultiModeDrtConfigGroup multiModeDrtConfigGroup = new MultiModeDrtConfigGroup();
        DrtWithExtensionsConfigGroup drtConfig = new DrtWithExtensionsConfigGroup();
        drtConfig.setUseModeFilteredSubnetwork(true);
        drtConfig.setMode(TransportMode.drt);
        drtConfig.setVehiclesFile(fleet.toString());
        drtConfig.setStopDuration(30);
        drtConfig.setNumberOfThreads(8);
        drtConfig.addParameterSet(new RepeatedSelectiveInsertionSearchParams());

        DrtParallelInserterParams drtParallelInserterParams = new DrtParallelInserterParams();
        drtParallelInserterParams.setCollectionPeriod(15);
        drtParallelInserterParams.setMaxIterations(2);
        drtParallelInserterParams.setLogThreadActivity(true);
        drtParallelInserterParams.setMaxPartitions(8);
        drtConfig.addParameterSet(drtParallelInserterParams);

        multiModeDrtConfigGroup.addDrtConfigGroup(drtConfig);

        DrtOptimizationConstraintsParams constraintsParams = drtConfig.addOrGetDrtOptimizationConstraintsParams();
        DrtOptimizationConstraintsSetImpl constraintsSet = constraintsParams.addOrGetDefaultDrtOptimizationConstraintsSet();
        constraintsSet.setMaxTravelTimeAlpha(1.);
        constraintsSet.setMaxTravelTimeBeta(600);
        constraintsSet.setMaxWaitTime(600);

        // Resolution for rebalancing
        SquareGridZoneSystemParams zoneParams = new SquareGridZoneSystemParams();
        zoneParams.setCellSize(1000);
        drtConfig.addParameterSet(zoneParams);
        drtConfig.setOperationalScheme(DrtConfigGroup.OperationalScheme.door2door);
        config.addModule(multiModeDrtConfigGroup);

        ReplanningConfigGroup.StrategySettings strategy = new ReplanningConfigGroup.StrategySettings(Id.create("1", ReplanningConfigGroup.StrategySettings.class));
        strategy.setStrategyName("KeepLastSelected");
        strategy.setWeight(0.0);
        config.replanning().addStrategySettings(strategy);
        config.replanning().setMaxAgentPlanMemorySize(1);
        config.replanning().setFractionOfIterationsToDisableInnovation(0.0);

        ScoringConfigGroup.ModeParams drtParams = new ScoringConfigGroup.ModeParams("drt");
        drtParams.setMarginalUtilityOfTraveling(-6.0);
        drtParams.setConstant(0.0);
        config.scoring().addModeParams(drtParams);

        ScoringConfigGroup.ActivityParams homeParams = new ScoringConfigGroup.ActivityParams("home");
        homeParams.setTypicalDuration(12 * 3600);
        config.scoring().addActivityParams(homeParams);

        ScoringConfigGroup.ActivityParams work = new ScoringConfigGroup.ActivityParams("work");
        work.setTypicalDuration(8 * 3600);
        config.scoring().addActivityParams(work);

        return config;
    }
}
