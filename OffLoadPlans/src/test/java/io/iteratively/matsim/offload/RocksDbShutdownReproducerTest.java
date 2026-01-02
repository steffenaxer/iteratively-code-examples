package io.iteratively.matsim.offload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minimal test to reproduce the shutdown hang issue with RocksDB.
 * Creates a small scenario with multiple plans per person to trigger
 * plan deletion during shutdown.
 */
public class RocksDbShutdownReproducerTest {
    
    @RegisterExtension
    private MatsimTestUtils utils = new MatsimTestUtils();
    
    @Test
    public void testShutdownWithPlanDeletion() {
        Config config = ConfigUtils.createConfig();
        config.controller().setLastIteration(2);
        config.controller().setOutputDirectory(utils.getOutputDirectory());
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.replanning().setMaxAgentPlanMemorySize(2); // Force plan deletion
        
        Scenario scenario = ScenarioUtils.createScenario(config);
        
        // Create minimal network
        Network network = scenario.getNetwork();
        NetworkFactory nf = network.getFactory();
        Node n1 = nf.createNode(Id.createNodeId("n1"), scenario.createCoord(0, 0));
        Node n2 = nf.createNode(Id.createNodeId("n2"), scenario.createCoord(1000, 0));
        network.addNode(n1);
        network.addNode(n2);
        Link link = nf.createLink(Id.createLinkId("link1"), n1, n2);
        link.setLength(1000);
        link.setFreespeed(10);
        link.setCapacity(1000);
        network.addLink(link);
        
        // Create population with 1000 persons, each with 3 initial plans
        Population population = scenario.getPopulation();
        PopulationFactory pf = population.getFactory();
        
        for (int i = 0; i < 1000; i++) {
            Person person = pf.createPerson(Id.createPersonId("person_" + i));
            
            // Create 3 plans per person (will trigger deletion when max=2)
            for (int p = 0; p < 3; p++) {
                Plan plan = pf.createPlan();
                Activity act1 = pf.createActivityFromLinkId("home", Id.createLinkId("link1"));
                act1.setEndTime(8 * 3600 + p * 600); // Different times
                plan.addActivity(act1);
                Leg leg = pf.createLeg("car");
                plan.addLeg(leg);
                Activity act2 = pf.createActivityFromLinkId("work", Id.createLinkId("link1"));
                plan.addActivity(act2);
                person.addPlan(plan);
            }
            person.setSelectedPlan(person.getPlans().get(0));
            population.addPerson(person);
        }
        
        // Configure offload with RocksDB
        File storeDir = new File(utils.getOutputDirectory(), "planstore");
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        offloadConfig.setStoreDirectory(storeDir.getAbsolutePath());
        offloadConfig.setCacheEntries(100);
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
        
        // Add simple replanning
        config.replanning().addStrategySettings(
            ConfigUtils.addOrGetModule(config, org.matsim.core.config.groups.ReplanningConfigGroup.class)
                .createStrategySettings()
                .setStrategyName("ChangeExpBeta")
                .setWeight(1.0)
        );
        
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());
        
        System.out.println("========== STARTING SIMULATION ==========");
        controler.run();
        System.out.println("========== SIMULATION COMPLETED ==========");
        
        File rocksDbDir = new File(storeDir, "rocksdb");
        assertTrue(rocksDbDir.exists(), "RocksDB directory should exist");
    }
}
