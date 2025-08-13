package io.iteratively.matsim;

import org.apache.commons.cli.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
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
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.NetworkUtils;

import java.nio.file.Path;

/**
 * @author steffenaxer
 */
public class SimulationExecutor {

    public static void main(String[] args) throws ParseException {
        Options options = new Options();

        Option configFileOption = new Option("c", "config", true, "Path to MATSim config file");
        configFileOption.setRequired(true);
        options.addOption(configFileOption);

        Option outputDirOption = new Option("o", "output-dir", true, "Working directory for output");
        outputDirOption.setRequired(true);
        options.addOption(outputDirOption);


        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        String configFilePath = cmd.getOptionValue("c");
        String outputDir = cmd.getOptionValue("o");

        Config config = ConfigUtils.loadConfig(configFilePath);
        config.controller().setOutputDirectory(outputDir);

        Controler controller = DrtControlerCreator.createControler(config, false);
        ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class).getModalElements().forEach(drtConfig ->
                controller.addOverridingQSimModule(new ParallelRequestInserterModule(drtConfig))
        );

        controller.run();
    }
}
