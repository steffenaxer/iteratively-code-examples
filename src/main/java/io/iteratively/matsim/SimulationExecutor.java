package io.iteratively.matsim;

import org.apache.commons.cli.*;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.extension.insertion.spatialFilter.SpatialFilterInsertionSearchQSimModule;
import org.matsim.contrib.drt.optimizer.insertion.parallel.ParallelRequestInserterModule;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;

import java.nio.file.Path;
import java.util.UUID;

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

        Config config = ConfigUtils.loadConfig(configFilePath, new MultiModeDrtConfigGroup(DrtWithExtensionsConfigGroup::new), new DvrpConfigGroup());
        config.controller().setOutputDirectory(Path.of(outputDir, UUID.randomUUID().toString()).toString());

        Controler controller = DrtControlerCreator.createControler(config, false);
        ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class).getModalElements().forEach(drtConfig -> {
                    controller.addOverridingQSimModule(new ParallelRequestInserterModule(drtConfig));
                    controller.addOverridingQSimModule(new SpatialFilterInsertionSearchQSimModule(drtConfig));
                }
        );

        controller.run();
    }
}
