package io.iteratively.matsim.offload.example;

import io.iteratively.matsim.offload.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.io.File;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for OffloadModuleExample that verifies:
 * 1. The example runs successfully
 * 2. At most 1 plan is materialized per person at runtime
 * 3. All plans are kept as proxies (for selector functionality)
 */
public class OffloadModuleExampleTest {

    @RegisterExtension
    private MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    public void testExampleRunsSuccessfully() {
        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
        Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(scenarioUrl, "config_default.xml"));

        File storeDir = new File(utils.getOutputDirectory(), "planstore");
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        offloadConfig.setStoreDirectory(storeDir.getAbsolutePath());
        offloadConfig.setCacheEntries(2000);

        config.controller().setOutputDirectory(utils.getOutputDirectory());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(2);
        config.replanning().setMaxAgentPlanMemorySize(3);

        Scenario scenario = ScenarioUtils.loadScenario(config);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());

        controler.addControlerListener(new MaterializationValidator());

        controler.run();

        File dbFile = new File(storeDir, OffloadConfigGroup.MAPDB_FILE_NAME);
        assertTrue(dbFile.exists(), "MapDB file should exist");
        assertTrue(dbFile.length() > 0, "MapDB file should contain data");
    }

    @Test
    public void testAtMostOnePlanMaterializedPerPerson() {
        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
        Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(scenarioUrl, "config_default.xml"));

        File storeDir = new File(utils.getOutputDirectory(), "planstore");
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        offloadConfig.setStoreDirectory(storeDir.getAbsolutePath());
        offloadConfig.setCacheEntries(100);

        config.controller().setOutputDirectory(utils.getOutputDirectory());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(3);
        config.replanning().setMaxAgentPlanMemorySize(5);

        Scenario scenario = ScenarioUtils.loadScenario(config);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());

        AtomicInteger maxMaterializedPlans = new AtomicInteger(0);
        AtomicInteger maxMaterializedPerPerson = new AtomicInteger(0);

        controler.addControlerListener(new IterationStartsListener() {
            @Override
            public void notifyIterationStarts(IterationStartsEvent event) {
                var pop = event.getServices().getScenario().getPopulation();

                final int[] totalMaterialized = {0};
                final int[] maxPerPerson = {0};

                for (Person person : pop.getPersons().values()) {
                    int materializedForPerson = 0;

                    for (Plan plan : person.getPlans()) {
                        if (plan instanceof PlanProxy proxy) {
                            if (proxy.isMaterialized()) {
                                materializedForPerson++;
                            }
                        }
                    }

                    totalMaterialized[0] += materializedForPerson;
                    maxPerPerson[0] = Math.max(maxPerPerson[0], materializedForPerson);
                }

                maxMaterializedPlans.updateAndGet(v -> Math.max(v, totalMaterialized[0]));
                maxMaterializedPerPerson.updateAndGet(v -> Math.max(v, maxPerPerson[0]));

                System.out.println("Iteration " + event.getIteration() +
                        " start: Total materialized=" + totalMaterialized[0] +
                        ", Max per person=" + maxPerPerson[0]);
            }
        });

        controler.addControlerListener(new IterationEndsListener() {
            @Override
            public void notifyIterationEnds(IterationEndsEvent event) {
                var pop = event.getServices().getScenario().getPopulation();

                int totalMaterialized = 0;

                for (Person person : pop.getPersons().values()) {
                    for (Plan plan : person.getPlans()) {
                        if (plan instanceof PlanProxy proxy) {
                            if (proxy.isMaterialized()) {
                                totalMaterialized++;
                            }
                        }
                    }
                }

                System.out.println("Iteration " + event.getIteration() +
                        " end: Total materialized=" + totalMaterialized);

                assertEquals(0, totalMaterialized,
                        "After iteration end, all plans should be dematerialized");
            }
        });

        controler.run();

        assertTrue(maxMaterializedPerPerson.get() <= 1,
                "At most 1 plan should be materialized per person, but found: " +
                        maxMaterializedPerPerson.get());

        System.out.println("\nTest Summary:");
        System.out.println("Max materialized plans total: " + maxMaterializedPlans.get());
        System.out.println("Max materialized per person: " + maxMaterializedPerPerson.get());
    }

    private static class MaterializationValidator implements IterationStartsListener, IterationEndsListener {

        @Override
        public void notifyIterationStarts(IterationStartsEvent event) {
            var pop = event.getServices().getScenario().getPopulation();

            for (Person person : pop.getPersons().values()) {
                int materializedCount = 0;
                int proxyCount = 0;

                for (Plan plan : person.getPlans()) {
                    if (plan instanceof PlanProxy proxy) {
                        proxyCount++;
                        if (proxy.isMaterialized()) {
                            materializedCount++;
                        }
                    }
                }

                assertTrue(proxyCount > 0, "Person should have proxy plans");
                assertTrue(materializedCount <= 1,
                        "At most 1 plan should be materialized per person at iteration start, found: " +
                                materializedCount + " for person " + person.getId());
            }
        }

        @Override
        public void notifyIterationEnds(IterationEndsEvent event) {
            var pop = event.getServices().getScenario().getPopulation();

            for (Person person : pop.getPersons().values()) {
                int materializedCount = 0;

                for (Plan plan : person.getPlans()) {
                    if (plan instanceof PlanProxy proxy) {
                        if (proxy.isMaterialized()) {
                            materializedCount++;
                        }
                    }
                }

                assertEquals(0, materializedCount,
                        "After iteration end, no plans should be materialized for person " + person.getId());
            }
        }
    }
}