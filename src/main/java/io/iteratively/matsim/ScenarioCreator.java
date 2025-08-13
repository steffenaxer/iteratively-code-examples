package io.iteratively.matsim;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.optimizer.insertion.repeatedselective.RepeatedSelectiveInsertionSearchParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
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

        Files.createDirectories(networkDir);
        Files.createDirectories(plansDir);
        Files.createDirectories(fleetDir);
        Files.createDirectories(outputDir);

        // 1. Netzwerk erstellen
        NetworkConverter.createDefaultMATSimNetwork(
                networkDir, osmUrl, networkKey, epsg,
                -87.9401, 41.6445, -87.5241, 42.0230 // Beispiel: Chicago Bounding Box
        );

        PlansConverter.run(token, plansDir, date, epsg, tract);

        // 3. Config vorbereiten
        String networkFile = networkDir.resolve(networkKey + ".network.xml.gz").toString();
        String plansFile = plansDir.resolve("plans.xml.gz").toString();
        String fleetFile = fleetDir.resolve("fleet.xml.gz").toString();
        Config config = prepareConfig(networkFile, 2000, 24 * 3600, 2, fleetFile);
        ConfigUtils.writeConfig(config, String.valueOf(workDir.resolve("config.xml")));
        config.network().setInputFile(networkFile);
        config.plans().setInputFile(plansFile);
        config.controller().setOutputDirectory(outputDir.toString());

    }

    public static Config prepareConfig(String networkFile, int numberOfVehicles, double endTime, int iterations, String fleetFile) {
        Config config = ConfigUtils.createConfig();

        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(iterations);

        config.addModule(new DvrpConfigGroup());
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.global().setNumberOfThreads(4);
        config.qsim().setEndTime(endTime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);

        Network network = NetworkUtils.readNetwork(networkFile);
        Path fleet = FleetGenerator.generateFleet(network, numberOfVehicles, 6, endTime, fleetFile);

        MultiModeDrtConfigGroup multiModeDrtConfigGroup = new MultiModeDrtConfigGroup();
        DrtWithExtensionsConfigGroup drtConfig = new DrtWithExtensionsConfigGroup();
        drtConfig.setVehiclesFile(fleet.toString());
        drtConfig.setStopDuration(30);
        drtConfig.addParameterSet(new RepeatedSelectiveInsertionSearchParams());

        multiModeDrtConfigGroup.addDrtConfigGroup(drtConfig);

        DrtOptimizationConstraintsParams constraintsParams = drtConfig.addOrGetDrtOptimizationConstraintsParams();
        DrtOptimizationConstraintsSetImpl constraintsSet = constraintsParams.addOrGetDefaultDrtOptimizationConstraintsSet();
        constraintsSet.setMaxTravelTimeAlpha(1.);
        constraintsSet.setMaxTravelTimeBeta(600);
        constraintsSet.setMaxWaitTime(600);

        SquareGridZoneSystemParams zoneParams = new SquareGridZoneSystemParams();
        zoneParams.setCellSize(500);

        drtConfig.setNumberOfThreads(4);
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

        return config;
    }
}
