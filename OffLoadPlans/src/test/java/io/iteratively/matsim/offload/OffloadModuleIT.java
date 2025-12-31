package io.iteratively.matsim.offload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class OffloadModuleIT {

    @TempDir
    Path tempDir;

    @Test
    public void testOffloadWithSiouxfalls() {
        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
        Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(scenarioUrl, "config_default.xml"));

        Path storeDir = tempDir.resolve("planstore");
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        offloadConfig.setStoreDirectory(storeDir.toString());
        offloadConfig.setCacheEntries(2000);

        config.controller().setOutputDirectory(tempDir.resolve("output").toString());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(2);

        Scenario scenario = ScenarioUtils.loadScenario(config);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());
        controler.run();

        File dbFile = storeDir.resolve(OffloadConfigGroup.DB_FILE_NAME).toFile();

        assertTrue(dbFile.exists(), "MapDB file should exist");
        assertTrue(dbFile.length() > 0, "MapDB file should contain data");

        try (MapDbPlanStore store = new MapDbPlanStore(dbFile, scenario,
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

        Path storeDir = tempDir.resolve("planstore2");
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        offloadConfig.setStoreDirectory(storeDir.toString());

        config.controller().setOutputDirectory(tempDir.resolve("output2").toString());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(1);

        Scenario scenario = ScenarioUtils.loadScenario(config);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());
        controler.run();

        File dbFile = storeDir.resolve(OffloadConfigGroup.DB_FILE_NAME).toFile();

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
