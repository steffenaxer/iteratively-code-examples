package io.iteratively.matsim;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.optimizer.insertion.DrtInsertionSearchParams;
import org.matsim.contrib.drt.optimizer.insertion.parallel.ParallelRequestInserterModule;
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
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.NetworkUtils;

import java.nio.file.Path;
import java.util.UUID;

/**
 * @author steffenaxer
 */
public class SimulationExecutor {

    public static void main(String[] args) {
        String workDir = args[0];
        Config config = prepareConfig( 2000, 2, new RepeatedSelectiveInsertionSearchParams(), Path.of(workDir, UUID.randomUUID().toString()).toString());
        DrtWithExtensionsConfigGroup drtConfig = (DrtWithExtensionsConfigGroup) ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class).getModalElements().iterator().next();
        Controler controller = DrtControlerCreator.createControler(config, false);
        controller.addOverridingQSimModule(new ParallelRequestInserterModule(drtConfig));
    }

    public static Config prepareConfig(int numberOfVehicles, int iterations, DrtInsertionSearchParams insertionSearch, String outputPath) {
        double endTime = 3600 * 24.;
        Config config = ConfigUtils.createConfig();

        config.controller().setOutputDirectory(Path.of(outputPath).resolve("output").toString());
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(iterations);

        config.addModule(new DvrpConfigGroup());
        config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
        config.global().setNumberOfThreads(4);
        config.qsim().setEndTime(endTime);
        config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);

        Network network = NetworkUtils.readNetwork(config.network().getInputFile(), config);
        Path fleet = FleetGenerator.generateFleet(network, numberOfVehicles, 6, endTime, Path.of(outputPath,"fleet","fleet.xml").toString());

        MultiModeDrtConfigGroup multiModeDrtConfigGroup = new MultiModeDrtConfigGroup();
        DrtWithExtensionsConfigGroup drtConfig = new DrtWithExtensionsConfigGroup();
        drtConfig.setVehiclesFile(fleet.toString());
        drtConfig.setStopDuration(30);
        drtConfig.addParameterSet(insertionSearch);

        multiModeDrtConfigGroup.addDrtConfigGroup(drtConfig);

        DrtOptimizationConstraintsParams constraintsParams = drtConfig.addOrGetDrtOptimizationConstraintsParams();
        DrtOptimizationConstraintsSetImpl constraintsSet = constraintsParams.addOrGetDefaultDrtOptimizationConstraintsSet();
        constraintsSet.setMaxTravelTimeAlpha(2.);
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
