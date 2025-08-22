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
import org.matsim.contrib.drt.extension.insertion.spatialFilter.DrtSpatialRequestFleetFilterParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.optimizer.insertion.parallel.DrtParallelInserterParams;
import org.matsim.contrib.drt.optimizer.insertion.repeatedselective.RepeatedSelectiveInsertionSearchParams;
import org.matsim.contrib.drt.optimizer.rebalancing.RebalancingParams;
import org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * ScenarioCreator sets up a MATSim scenario using Chicago TNP data.
 * Author: steffenaxer
 */
public class ScenarioCreator {
    public static void main(String[] args) throws Exception {
        // Parse command-line arguments
        Options options = new Options();
        options.addRequiredOption("w", "workdir", true, "Working directory for scenario");
        options.addRequiredOption("u", "url", true, "URL to .osm.pbf file");
        options.addRequiredOption("k", "key", true, "Network key");
        options.addRequiredOption("e", "epsg", true, "EPSG code");
        options.addRequiredOption("S", "startDate", true, "Start date for TNP data (YYYY-MM-DD)");
        options.addOption("E", "endDate", true, "End date for TNP data (YYYY-MM-DD)");
        options.addRequiredOption("t", "token", true, "API token for Chicago TNP");
        options.addRequiredOption("bbox", "bbox", true, "Bounding box as xmin,ymin,xmax,ymax");
        options.addOption("c", "tract", true, "Census tract file");
        options.addOption("r", "sampleRate", true, "Rate to sample trips. ");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        // Extract parameters
        Path workDir = Paths.get(cmd.getOptionValue("workdir"));
        String networkKey = cmd.getOptionValue("key");
        String epsg = cmd.getOptionValue("epsg");
        String startDateStr = cmd.getOptionValue("startDate");
        String endDateStr = cmd.getOptionValue("endDate");
        String token = cmd.getOptionValue("token");
        String tract = cmd.getOptionValue("tract");
        double sampleRate = Double.parseDouble(cmd.getOptionValue("sampleRate","1.0"));
        URL osmUrl = new URL(cmd.getOptionValue("url"));

        // Parse bounding box
        String[] bboxParts = cmd.getOptionValue("bbox").split(",");
        if (bboxParts.length != 4) {
            throw new IllegalArgumentException("Bounding box must have 4 comma-separated values: xmin,ymin,xmax,ymax");
        }
        double xmin = Double.parseDouble(bboxParts[0]);
        double ymin = Double.parseDouble(bboxParts[1]);
        double xmax = Double.parseDouble(bboxParts[2]);
        double ymax = Double.parseDouble(bboxParts[3]);

        // Create directories
        Path networkDir = workDir.resolve("network");
        Path plansDir = workDir.resolve("plans");
        Path fleetDir = workDir.resolve("fleet");
        Path outputDir = workDir.resolve("output");
        Files.createDirectories(networkDir);
        Files.createDirectories(plansDir);
        Files.createDirectories(fleetDir);
        Files.createDirectories(outputDir);

        // Generate network and plans
        NetworkConverter.createDefaultMATSimNetwork(networkDir, osmUrl, networkKey, epsg, xmin, ymin, xmax, ymax, TransportMode.drt);
        PlansConverter.run(token, plansDir, startDateStr, endDateStr, epsg, tract, sampleRate);

        // Prepare config
        String networkFile = networkDir.resolve(networkKey + ".network.xml.gz").toString();
        String plansFile = plansDir.resolve("plans.xml.gz").toString();
        String fleetFile = fleetDir.resolve("fleet.xml.gz").toString();

        LocalDate startDate = LocalDate.parse(startDateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        LocalDate endDate = endDateStr !=  null ? LocalDate.parse(endDateStr, DateTimeFormatter.ISO_LOCAL_DATE) : null;

        int daysBetween;
        if (endDate != null) {
            daysBetween = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        } else {
            daysBetween = 1;
        }
        Config config = prepareConfig(networkFile, 3400, 1, 24 * 3600 * daysBetween, 2, fleetFile);

        // Finalize config
        config.global().setCoordinateSystem(epsg);
        config.network().setInputFile(networkFile);
        config.plans().setInputFile(plansFile);
        config.controller().setOutputDirectory(outputDir.toString());
        ConfigUtils.writeConfig(config, workDir.resolve("config.xml").toString());
    }

    public static Config prepareConfig(String networkFile, int numberOfVehicles, int seats, double endTime, int iterations, String fleetFile) {
        Config config = ConfigUtils.createConfig();
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(iterations);

        // DVRP setup
        DvrpConfigGroup dvrpConfigGroup = new DvrpConfigGroup();
        dvrpConfigGroup.setNetworkModes(Set.of(TransportMode.car, TransportMode.drt));
        DvrpTravelTimeMatrixParams dvrpTravelTimeMatrixParams = new DvrpTravelTimeMatrixParams();
        dvrpTravelTimeMatrixParams.setMaxNeighborDistance(0);
        SquareGridZoneSystemParams dvrpZones = new SquareGridZoneSystemParams();
        dvrpZones.setCellSize(1000);
        dvrpTravelTimeMatrixParams.addParameterSet(dvrpZones);
        dvrpConfigGroup.addParameterSet(dvrpTravelTimeMatrixParams);
        config.addModule(dvrpConfigGroup);

        // QSim setup
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.global().setNumberOfThreads(12);
        config.qsim().setEndTime(endTime);
        config.qsim().setNumberOfThreads(6);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);

        // Fleet generation
        Network network = NetworkUtils.readNetwork(networkFile);
        Path fleet = FleetGenerator.generateFleet(network, numberOfVehicles, seats, endTime, fleetFile, TransportMode.drt);

        // DRT setup
        MultiModeDrtConfigGroup multiModeDrtConfigGroup = new MultiModeDrtConfigGroup();
        DrtWithExtensionsConfigGroup drtConfig = new DrtWithExtensionsConfigGroup();
        drtConfig.setUseModeFilteredSubnetwork(true);
        drtConfig.setMode(TransportMode.drt);
        drtConfig.setVehiclesFile(fleet.toString());
        drtConfig.setStopDuration(30);
        drtConfig.addParameterSet(new RepeatedSelectiveInsertionSearchParams());

        // Analysis zone system
        SquareGridZoneSystemParams analysisZones = new SquareGridZoneSystemParams();
        analysisZones.setCellSize(3000);
        drtConfig.addParameterSet(analysisZones);

        // Parallel insertion
        DrtParallelInserterParams inserterParams = new DrtParallelInserterParams();
        inserterParams.setCollectionPeriod(15);
        inserterParams.setMaxIterations(2);
        inserterParams.setLogThreadActivity(false);
        inserterParams.setMaxPartitions(6);
        inserterParams.setRequestsPartitioner(DrtParallelInserterParams.RequestsPartitioner.LoadAwareRoundRobinRequestsPartitioner);
        inserterParams.setVehiclesPartitioner(DrtParallelInserterParams.VehiclesPartitioner.ShiftingRoundRobinVehicleEntryPartitioner);
        drtConfig.addParameterSet(inserterParams);

        DrtSpatialRequestFleetFilterParams drtSpatialRequestFleetFilterParams = new DrtSpatialRequestFleetFilterParams();
        drtConfig.addParameterSet(drtSpatialRequestFleetFilterParams);

        // Rebalancing
        RebalancingParams rebalancingParams = new RebalancingParams();
        MinCostFlowRebalancingStrategyParams strategyParams = new MinCostFlowRebalancingStrategyParams();
        strategyParams.setTargetAlpha(0.3);
        strategyParams.setTargetBeta(0.4);
        SquareGridZoneSystemParams zoneSystemParams = new SquareGridZoneSystemParams();
        zoneSystemParams.setCellSize(1000);
        rebalancingParams.addParameterSet(zoneSystemParams);
        rebalancingParams.addParameterSet(strategyParams);
        drtConfig.addParameterSet(rebalancingParams);

        // Optimization constraints
        DrtOptimizationConstraintsParams constraintsParams = drtConfig.addOrGetDrtOptimizationConstraintsParams();
        DrtOptimizationConstraintsSetImpl constraintsSet = constraintsParams.addOrGetDefaultDrtOptimizationConstraintsSet();
        constraintsSet.setMaxTravelTimeAlpha(1.);
        constraintsSet.setMaxTravelTimeBeta(600);
        constraintsSet.setMaxWaitTime(600);

        // Operational scheme
        drtConfig.setOperationalScheme(DrtConfigGroup.OperationalScheme.door2door);
        multiModeDrtConfigGroup.addDrtConfigGroup(drtConfig);
        config.addModule(multiModeDrtConfigGroup);

        // Replanning
        ReplanningConfigGroup.StrategySettings strategy = new ReplanningConfigGroup.StrategySettings(Id.create("1", ReplanningConfigGroup.StrategySettings.class));
        strategy.setStrategyName("KeepLastSelected");
        strategy.setWeight(0.0);
        config.replanning().addStrategySettings(strategy);
        config.replanning().setMaxAgentPlanMemorySize(1);
        config.replanning().setFractionOfIterationsToDisableInnovation(0.0);

        // Scoring
        ScoringConfigGroup.ModeParams drtParams = new ScoringConfigGroup.ModeParams("drt");
        drtParams.setMarginalUtilityOfTraveling(-6.0);
        drtParams.setConstant(0.0);
        config.scoring().addModeParams(drtParams);

        ScoringConfigGroup.ActivityParams homeParams = new ScoringConfigGroup.ActivityParams("home");
        homeParams.setTypicalDuration(12 * 3600);
        config.scoring().addActivityParams(homeParams);

        ScoringConfigGroup.ActivityParams workParams = new ScoringConfigGroup.ActivityParams("work");
        workParams.setTypicalDuration(8 * 3600);
        config.scoring().addActivityParams(workParams);

        return config;
    }
}
