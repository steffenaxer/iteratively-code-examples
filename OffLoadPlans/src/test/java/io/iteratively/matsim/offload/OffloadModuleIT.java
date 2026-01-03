package io.iteratively.matsim.offload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.io.File;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

public class OffloadModuleIT {

    @RegisterExtension private MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    public void testOffloadWithSiouxfalls() {
        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
        Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(scenarioUrl, "config_default.xml"));

        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        offloadConfig.setCacheEntries(2000);
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
        // Store directory will default to outputDirectory/planstore

        config.controller().setOutputDirectory(utils.getOutputDirectory());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(20);
        config.replanning().setMaxAgentPlanMemorySize(3);

        // Use streaming approach to load scenario
        Scenario scenario = OffloadSupport.loadScenarioWithStreaming(config);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());
        controler.run();

        System.gc();
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        File storeDir = new File(utils.getOutputDirectory(), "planstore");
        File rocksDbDir = new File(storeDir, OffloadConfigGroup.ROCKSDB_DIR_NAME);

        assertTrue(rocksDbDir.exists(), "RocksDB directory should exist");
        assertTrue(rocksDbDir.isDirectory(), "RocksDB store should be a directory");

        try (RocksDbPlanStore store = new RocksDbPlanStore(rocksDbDir, scenario,
                scenario.getConfig().replanning().getMaxAgentPlanMemorySize())) {
            int storedPlans = 0;
            for (Person person : scenario.getPopulation().getPersons().values()) {
                var headers = store.listPlanHeaders(person.getId().toString());
                storedPlans += headers.size();
            }
            assertTrue(storedPlans > 0, "Plans should be stored in the store");
        }
    }

    @Test
    public void testPlanMaterializationAfterSimulation() {
        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
        Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(scenarioUrl, "config_default.xml"));

        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        // Storage backend defaults to MAPDB
        // Store directory will default to outputDirectory/planstore

        config.controller().setOutputDirectory(utils.getOutputDirectory());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(1);

        // Use streaming approach to load scenario
        Scenario scenario = OffloadSupport.loadScenarioWithStreaming(config);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());
        controler.run();

        File storeDir = new File(utils.getOutputDirectory(), "planstore");
        File dbFile = new File(storeDir, OffloadConfigGroup.MAPDB_FILE_NAME);

        try (MapDbPlanStore store = new MapDbPlanStore(dbFile, scenario,
                scenario.getConfig().replanning().getMaxAgentPlanMemorySize())) {
            String firstPersonId = scenario.getPopulation().getPersons().keySet()
                    .iterator().next().toString();

            var headers = store.listPlanHeaders(firstPersonId);
            assertFalse(headers.isEmpty(), "Person should have at least one plan");

            var header = headers.get(0);
            var plan = store.materialize(firstPersonId, header.planId);

            assertNotNull(plan, "Materialized plan should not be null");
            assertFalse(plan.getPlanElements().isEmpty(), "Plan should contain elements");
        }
    }
}
