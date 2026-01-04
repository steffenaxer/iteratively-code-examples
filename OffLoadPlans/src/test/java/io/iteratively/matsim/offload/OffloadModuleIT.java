package io.iteratively.matsim.offload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
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

        File storeDir = new File(utils.getOutputDirectory(), "planstore");
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        offloadConfig.setStoreDirectory(storeDir.getAbsolutePath());
        offloadConfig.setCacheEntries(2000);
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);

        config.controller().setOutputDirectory(utils.getOutputDirectory());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(20);
        config.replanning().setMaxAgentPlanMemorySize(3);

        Scenario scenario = ScenarioUtils.loadScenario(config);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());
        controler.run();

        System.gc();
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        File rocksDbDir = new File(storeDir, "rocksdb");

        assertTrue(rocksDbDir.exists(), "RocksDB directory should exist");
        assertTrue(rocksDbDir.isDirectory(), "RocksDB store should be a directory");

        try (RocksDbPlanStore store = new RocksDbPlanStore(rocksDbDir, scenario,
                scenario.getConfig().replanning().getMaxAgentPlanMemorySize())) {
            int storedPlans = 0;
            for (Person person : scenario.getPopulation().getPersons().values()) {
                var proxies = store.listPlanProxies(person);
                storedPlans += proxies.size();
            }
            assertTrue(storedPlans > 0, "Plans should be stored in the store");
        }
    }

    @Test
    public void testPlanMaterializationAfterSimulation() {
        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
        Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(scenarioUrl, "config_default.xml"));

        File storeDir = new File(utils.getOutputDirectory(), "planstore");
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        offloadConfig.setStoreDirectory(storeDir.getAbsolutePath());

        config.controller().setOutputDirectory(utils.getOutputDirectory());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(1);

        Scenario scenario = ScenarioUtils.loadScenario(config);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());
        controler.run();

        File dbFile = new File(storeDir, OffloadConfigGroup.DB_FILE_NAME);

        try (MapDbPlanStore store = new MapDbPlanStore(dbFile, scenario,
                scenario.getConfig().replanning().getMaxAgentPlanMemorySize())) {
            String firstPersonId = scenario.getPopulation().getPersons().keySet()
                    .iterator().next().toString();
            Person firstPerson = scenario.getPopulation().getPersons().get(
                    Id.createPersonId(firstPersonId));

            var proxies = store.listPlanProxies(firstPerson);
            assertFalse(proxies.isEmpty(), "Person should have at least one plan");

            var proxy = proxies.get(0);
            var plan = store.materialize(firstPersonId, proxy.getPlanId());

            assertNotNull(plan, "Materialized plan should not be null");
            assertFalse(plan.getPlanElements().isEmpty(), "Plan should contain elements");
        }
    }
}
