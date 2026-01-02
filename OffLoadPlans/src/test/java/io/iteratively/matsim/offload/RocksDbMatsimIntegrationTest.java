package io.iteratively.matsim.offload;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for RocksDB plan storage with MATSim.
 * Tests the complete workflow: simulation -> plan offloading -> population writing.
 */
public class RocksDbMatsimIntegrationTest {
    
    @TempDir
    Path tempDir;
    
    private Config config;
    private Scenario scenario;
    
    @BeforeEach
    public void setUp() {
        config = ConfigUtils.createConfig();
        
        // Minimal iterations for fast test
        config.controller().setFirstIteration(0);
        config.controller().setLastIteration(1);  // Only 2 iterations
        config.controller().setOutputDirectory(tempDir.resolve("output").toString());
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setWriteEventsInterval(0);
        config.controller().setWritePlansInterval(1);
        config.controller().setWriteEventsInterval(0);
        config.controller().setCreateGraphs(false);
        
        ScoringConfigGroup.ActivityParams homeAct = new ScoringConfigGroup.ActivityParams("home");
        homeAct.setTypicalDuration(12 * 3600);
        config.scoring().addActivityParams(homeAct);
        
        ScoringConfigGroup.ActivityParams workAct = new ScoringConfigGroup.ActivityParams("work");
        workAct.setTypicalDuration(8 * 3600);
        config.scoring().addActivityParams(workAct);
        
        scenario = ScenarioUtils.createScenario(config);
        createSimpleNetwork();
        createPopulation();
    }
    
    private void createSimpleNetwork() {
        Network network = scenario.getNetwork();
        NetworkFactory nf = network.getFactory();
        
        Node node1 = nf.createNode(Id.createNodeId("1"), new Coord(0, 0));
        Node node2 = nf.createNode(Id.createNodeId("2"), new Coord(1000, 0));
        Node node3 = nf.createNode(Id.createNodeId("3"), new Coord(1000, 1000));
        Node node4 = nf.createNode(Id.createNodeId("4"), new Coord(0, 1000));
        
        network.addNode(node1);
        network.addNode(node2);
        network.addNode(node3);
        network.addNode(node4);
        
        Link link12 = nf.createLink(Id.createLinkId("1-2"), node1, node2);
        link12.setLength(1000);
        link12.setFreespeed(50.0 / 3.6);
        link12.setCapacity(2000);
        link12.setNumberOfLanes(1);
        network.addLink(link12);
        
        Link link23 = nf.createLink(Id.createLinkId("2-3"), node2, node3);
        link23.setLength(1000);
        link23.setFreespeed(50.0 / 3.6);
        link23.setCapacity(2000);
        link23.setNumberOfLanes(1);
        network.addLink(link23);
        
        Link link34 = nf.createLink(Id.createLinkId("3-4"), node3, node4);
        link34.setLength(1000);
        link34.setFreespeed(50.0 / 3.6);
        link34.setCapacity(2000);
        link34.setNumberOfLanes(1);
        network.addLink(link34);
        
        Link link41 = nf.createLink(Id.createLinkId("4-1"), node4, node1);
        link41.setLength(1000);
        link41.setFreespeed(50.0 / 3.6);
        link41.setCapacity(2000);
        link41.setNumberOfLanes(1);
        network.addLink(link41);
    }
    
    private void createPopulation() {
        Population population = scenario.getPopulation();
        PopulationFactory pf = population.getFactory();
        
        // Only 3 agents for fast test
        for (int i = 0; i < 3; i++) {
            Person person = pf.createPerson(Id.createPersonId("person_" + i));
            
            Plan plan = pf.createPlan();
            
            Activity homeAct1 = pf.createActivityFromLinkId("home", Id.createLinkId("1-2"));
            homeAct1.setEndTime(7 * 3600 + i * 60);
            plan.addActivity(homeAct1);
            
            plan.addLeg(pf.createLeg("car"));
            
            Activity workAct = pf.createActivityFromLinkId("work", Id.createLinkId("2-3"));
            workAct.setEndTime(17 * 3600 + i * 60);
            plan.addActivity(workAct);
            
            plan.addLeg(pf.createLeg("car"));
            
            Activity homeAct2 = pf.createActivityFromLinkId("home", Id.createLinkId("1-2"));
            plan.addActivity(homeAct2);
            
            person.addPlan(plan);
            person.setSelectedPlan(plan);
            
            population.addPerson(person);
        }
    }
    
    @Test
    public void testRocksDbWithMatsimSimulation() {
        OffloadConfigGroup offloadConfig = new OffloadConfigGroup();
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
        offloadConfig.setStoreDirectory(tempDir.resolve("rocksdb-store").toString());
        offloadConfig.setCacheEntries(5);
        config.addModule(offloadConfig);
        
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());
        
        controler.run();
        
        File outputPlansFile = new File(config.controller().getOutputDirectory(), 
            "output_plans.xml.gz");
        assertTrue(outputPlansFile.exists(), "Output plans file should exist");
        
        Scenario outputScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(outputScenario).readFile(outputPlansFile.getAbsolutePath());
        
        assertEquals(10, outputScenario.getPopulation().getPersons().size(), 
            "All persons should be in output");
        
        assertEquals(3, outputScenario.getPopulation().getPersons().size(), 
            "All persons should be in output");
        
        for (Person person : outputScenario.getPopulation().getPersons().values()) {
            assertFalse(person.getPlans().isEmpty(), 
                "Person " + person.getId() + " should have at least one plan");
            
            Plan selectedPlan = person.getSelectedPlan();
            assertNotNull(selectedPlan, 
                "Person " + person.getId() + " should have a selected plan");
            assertNotNull(selectedPlan.getPlanElements(), 
                "Plan should have plan elements");
            assertEquals(5, selectedPlan.getPlanElements().size(), 
                "Plan should have 5 elements (3 activities, 2 legs)");
        }
    }
    
    @Test
    public void testMapDbWithMatsimSimulation() {
        OffloadConfigGroup offloadConfig = new OffloadConfigGroup();
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.MAPDB);
        offloadConfig.setStoreDirectory(tempDir.resolve("mapdb-store").toString());
        offloadConfig.setCacheEntries(5);
        config.addModule(offloadConfig);
        
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());
        
        controler.run();
        
        File outputPlansFile = new File(config.controller().getOutputDirectory(), 
            "output_plans.xml.gz");
        assertTrue(outputPlansFile.exists(), "Output plans file should exist");
        
        Scenario outputScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(outputScenario).readFile(outputPlansFile.getAbsolutePath());
        
        assertEquals(3, outputScenario.getPopulation().getPersons().size(), 
            "All persons should be in output");
    }
    
    @Test
    public void testPopulationWritingWithOffloadedPlans() throws Exception {
        OffloadConfigGroup offloadConfig = new OffloadConfigGroup();
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
        offloadConfig.setStoreDirectory(tempDir.resolve("rocksdb-write-test").toString());
        offloadConfig.setCacheEntries(3);
        config.addModule(offloadConfig);
        
        File dbDir = new File(offloadConfig.getStoreDirectory());
        dbDir.mkdirs();
        
        RocksDbPlanStore store = new RocksDbPlanStore(dbDir, scenario, 3);
        
        Population population = scenario.getPopulation();
        PopulationFactory pf = population.getFactory();
        
        // Only test 3 persons
        for (int i = 0; i < 3; i++) {
            Person person = population.getPersons().get(Id.createPersonId("person_" + i));
            if (person != null) {
                Plan originalPlan = person.getSelectedPlan();
                String planId = "plan_iter_0_" + i;
                
                store.putPlan(person.getId().toString(), planId, originalPlan, 
                    100.0 + i, 0, true);
                
                if (i % 2 == 0) {
                    store.commit();
                }
            }
        }
        
        store.commit();
        
        for (int i = 0; i < 3; i++) {
            Person person = population.getPersons().get(Id.createPersonId("person_" + i));
            if (person != null) {
                String planId = "plan_iter_0_" + i;
                Plan materialized = store.materialize(person.getId().toString(), planId);
                
                assertNotNull(materialized, 
                    "Plan for person_" + i + " should be retrievable");
                assertEquals(5, materialized.getPlanElements().size(),
                    "Materialized plan should have correct number of elements");
            }
        }
        
        store.close();
    }
}
