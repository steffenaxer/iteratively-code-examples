package io.iteratively.matsim.offload;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for RocksDB plan storage with MATSim.
 * Tests the complete workflow: simulation -> plan offloading -> population writing.
 */
public class RocksDbMatsimIntegrationTest {
    
    @RegisterExtension
    private MatsimTestUtils utils = new MatsimTestUtils();
    
    private Config config;
    private Scenario scenario;
    
    @BeforeEach
    public void setUp() {
        config = ConfigUtils.createConfig();
        
        // More iterations to test plan creation during simulation
        config.controller().setFirstIteration(0);
        config.controller().setLastIteration(5);  // 6 iterations total
        config.controller().setOutputDirectory(utils.getOutputDirectory());
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setWriteEventsInterval(0);
        config.controller().setWritePlansInterval(5);
        config.controller().setCreateGraphs(false);
        
        ScoringConfigGroup.ActivityParams homeAct = new ScoringConfigGroup.ActivityParams("home");
        homeAct.setTypicalDuration(12 * 3600);
        config.scoring().addActivityParams(homeAct);
        
        ScoringConfigGroup.ActivityParams workAct = new ScoringConfigGroup.ActivityParams("work");
        workAct.setTypicalDuration(8 * 3600);
        config.scoring().addActivityParams(workAct);
        
        // Add replanning strategies to create new plans during simulation
        ReplanningConfigGroup.StrategySettings strategySettings1 = new ReplanningConfigGroup.StrategySettings();
        strategySettings1.setStrategyName("ChangeExpBeta");
        strategySettings1.setWeight(0.7);
        config.replanning().addStrategySettings(strategySettings1);
        
        ReplanningConfigGroup.StrategySettings strategySettings2 = new ReplanningConfigGroup.StrategySettings();
        strategySettings2.setStrategyName("ReRoute");
        strategySettings2.setWeight(0.2);
        config.replanning().addStrategySettings(strategySettings2);
        
        ReplanningConfigGroup.StrategySettings strategySettings3 = new ReplanningConfigGroup.StrategySettings();
        strategySettings3.setStrategyName("TimeAllocationMutator");
        strategySettings3.setWeight(0.1);
        config.replanning().addStrategySettings(strategySettings3);
        
        // Set max plans to allow multiple plans per agent
        config.replanning().setMaxAgentPlanMemorySize(5);
        
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
        
        // 5 agents for more realistic test
        for (int i = 0; i < 5; i++) {
            Person person = pf.createPerson(Id.createPersonId("person_" + i));
            
            // Create 3 initial plans with different departure times per person
            for (int planIdx = 0; planIdx < 3; planIdx++) {
                Plan plan = pf.createPlan();
                
                Activity homeAct1 = pf.createActivityFromLinkId("home", Id.createLinkId("1-2"));
                homeAct1.setEndTime(7 * 3600 + i * 60 + planIdx * 300);  // Vary by person and plan
                plan.addActivity(homeAct1);
                
                plan.addLeg(pf.createLeg("car"));
                
                Activity workAct = pf.createActivityFromLinkId("work", Id.createLinkId("2-3"));
                workAct.setEndTime(17 * 3600 + i * 60 + planIdx * 300);
                plan.addActivity(workAct);
                
                plan.addLeg(pf.createLeg("car"));
                
                Activity homeAct2 = pf.createActivityFromLinkId("home", Id.createLinkId("1-2"));
                plan.addActivity(homeAct2);
                
                person.addPlan(plan);
                
                // Set first plan as selected
                if (planIdx == 0) {
                    person.setSelectedPlan(plan);
                }
            }
            
            population.addPerson(person);
        }
    }
    
    @Test
    public void testCompareAllStorageBackends() {
        // Run simulation with RocksDB
        Scenario rocksDbResult = runSimulationWithConfig("rocksdb", 
            OffloadConfigGroup.StorageBackend.ROCKSDB, true);
        
        // Run simulation with MapDB
        Scenario mapDbResult = runSimulationWithConfig("mapdb", 
            OffloadConfigGroup.StorageBackend.MAPDB, true);
        
        // Run simulation without offload
        Scenario noOffloadResult = runSimulationWithConfig("no-offload", 
            null, false);
        
        // Compare results - all should be identical
        compareScenarioResults(rocksDbResult, mapDbResult, "RocksDB", "MapDB");
        compareScenarioResults(rocksDbResult, noOffloadResult, "RocksDB", "No-Offload");
        compareScenarioResults(mapDbResult, noOffloadResult, "MapDB", "No-Offload");
    }
    
    private Scenario runSimulationWithConfig(String subfolder, 
                                              OffloadConfigGroup.StorageBackend backend, 
                                              boolean useOffload) {
        // Create fresh config and scenario for each run
        Config runConfig = ConfigUtils.createConfig();
        runConfig.controller().setFirstIteration(0);
        runConfig.controller().setLastIteration(10);  // 6 iterations
        runConfig.controller().setOutputDirectory(
            new File(utils.getOutputDirectory(), subfolder).toString());
        runConfig.controller().setOverwriteFileSetting(
            OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        runConfig.controller().setWriteEventsInterval(0);
        runConfig.controller().setWritePlansInterval(5);
        runConfig.controller().setCreateGraphs(false);
        
        ScoringConfigGroup.ActivityParams homeAct = new ScoringConfigGroup.ActivityParams("home");
        homeAct.setTypicalDuration(12 * 3600);
        runConfig.scoring().addActivityParams(homeAct);
        
        ScoringConfigGroup.ActivityParams workAct = new ScoringConfigGroup.ActivityParams("work");
        workAct.setTypicalDuration(8 * 3600);
        runConfig.scoring().addActivityParams(workAct);
        
        // Add multiple replanning strategies to create new plans
        ReplanningConfigGroup.StrategySettings strategySettings1 = new ReplanningConfigGroup.StrategySettings();
        strategySettings1.setStrategyName("ChangeExpBeta");
        strategySettings1.setWeight(0.7);
        runConfig.replanning().addStrategySettings(strategySettings1);
        
        ReplanningConfigGroup.StrategySettings strategySettings2 = new ReplanningConfigGroup.StrategySettings();
        strategySettings2.setStrategyName("ReRoute");
        strategySettings2.setWeight(0.2);
        runConfig.replanning().addStrategySettings(strategySettings2);
        
        ReplanningConfigGroup.StrategySettings strategySettings3 = new ReplanningConfigGroup.StrategySettings();
        strategySettings3.setStrategyName("TimeAllocationMutator");
        strategySettings3.setWeight(0.1);
        runConfig.replanning().addStrategySettings(strategySettings3);
        
        // Set max plans to allow multiple plans per agent
        runConfig.replanning().setMaxAgentPlanMemorySize(5);
        
        Scenario runScenario = ScenarioUtils.createScenario(runConfig);
        
        // Copy network
        Network network = runScenario.getNetwork();
        for (Node node : scenario.getNetwork().getNodes().values()) {
            network.addNode(network.getFactory().createNode(node.getId(), node.getCoord()));
        }
        for (Link link : scenario.getNetwork().getLinks().values()) {
            Link newLink = network.getFactory().createLink(
                link.getId(), 
                network.getNodes().get(link.getFromNode().getId()),
                network.getNodes().get(link.getToNode().getId()));
            newLink.setLength(link.getLength());
            newLink.setFreespeed(link.getFreespeed());
            newLink.setCapacity(link.getCapacity());
            newLink.setNumberOfLanes(link.getNumberOfLanes());
            network.addLink(newLink);
        }
        
        // Copy population with all initial plans
        Population population = runScenario.getPopulation();
        for (Person person : scenario.getPopulation().getPersons().values()) {
            Person newPerson = population.getFactory().createPerson(person.getId());
            Plan selectedPlan = null;
            for (Plan plan : person.getPlans()) {
                Plan newPlan = population.getFactory().createPlan();
                for (PlanElement pe : plan.getPlanElements()) {
                    if (pe instanceof Activity) {
                        Activity act = (Activity) pe;
                        Activity newAct = population.getFactory().createActivityFromLinkId(
                            act.getType(), act.getLinkId());
                        if (act.getEndTime().isDefined()) {
                            newAct.setEndTime(act.getEndTime().seconds());
                        }
                        newPlan.addActivity(newAct);
                    } else if (pe instanceof Leg) {
                        Leg leg = (Leg) pe;
                        newPlan.addLeg(population.getFactory().createLeg(leg.getMode()));
                    }
                }
                newPerson.addPlan(newPlan);
                // Remember which plan was selected in the original
                if (plan == person.getSelectedPlan()) {
                    selectedPlan = newPlan;
                }
            }
            newPerson.setSelectedPlan(selectedPlan != null ? selectedPlan : newPerson.getPlans().get(0));
            population.addPerson(newPerson);
        }
        
        if (useOffload) {
            OffloadConfigGroup offloadConfig = new OffloadConfigGroup();
            offloadConfig.setStorageBackend(backend);
            offloadConfig.setStoreDirectory(
                new File(runConfig.controller().getOutputDirectory(), "store").toString());
            offloadConfig.setCacheEntries(5);
            runConfig.addModule(offloadConfig);
        }
        
        Controler controler = new Controler(runScenario);
        if (useOffload) {
            controler.addOverridingModule(new OffloadModule());
        }
        
        controler.run();
        
        // Read output population
        File outputPlansFile = new File(runConfig.controller().getOutputDirectory(), 
            "output_plans.xml.gz");
        assertTrue(outputPlansFile.exists(), 
            "Output plans file should exist for " + subfolder);
        
        Scenario outputScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(outputScenario).readFile(outputPlansFile.getAbsolutePath());
        
        return outputScenario;
    }
    
    private void compareScenarioResults(Scenario scenario1, Scenario scenario2, 
                                         String name1, String name2) {
        assertEquals(scenario1.getPopulation().getPersons().size(), 
                     scenario2.getPopulation().getPersons().size(),
                     String.format("%s and %s should have same number of persons", name1, name2));
        
        for (Id<Person> personId : scenario1.getPopulation().getPersons().keySet()) {
            Person person1 = scenario1.getPopulation().getPersons().get(personId);
            Person person2 = scenario2.getPopulation().getPersons().get(personId);
            
            assertNotNull(person2, 
                String.format("Person %s should exist in both %s and %s", personId, name1, name2));
            
            assertEquals(person1.getPlans().size(), person2.getPlans().size(),
                String.format("Person %s should have same number of plans in %s and %s (had %d vs %d)", 
                    personId, name1, name2, person1.getPlans().size(), person2.getPlans().size()));
            
            // Verify each person has multiple plans (proving plan creation works)
            assertTrue(person1.getPlans().size() > 1,
                String.format("Person %s in %s should have multiple plans (has %d)", 
                    personId, name1, person1.getPlans().size()));
            
            Plan selectedPlan1 = person1.getSelectedPlan();
            Plan selectedPlan2 = person2.getSelectedPlan();
            
            assertNotNull(selectedPlan1, 
                String.format("Person %s should have selected plan in %s", personId, name1));
            assertNotNull(selectedPlan2, 
                String.format("Person %s should have selected plan in %s", personId, name2));
            
            assertEquals(selectedPlan1.getPlanElements().size(), 
                         selectedPlan2.getPlanElements().size(),
                String.format("Person %s selected plan should have same elements in %s and %s", 
                    personId, name1, name2));
            
            // Compare plan scores (should be identical for deterministic simulation)
            if (selectedPlan1.getScore() != null && selectedPlan2.getScore() != null) {
                assertEquals(selectedPlan1.getScore(), selectedPlan2.getScore(), 0.01,
                    String.format("Person %s should have same score in %s and %s", 
                        personId, name1, name2));
            }
            
            // Compare all plans (scores should match for all plans)
            for (int i = 0; i < person1.getPlans().size(); i++) {
                Plan plan1 = person1.getPlans().get(i);
                Plan plan2 = person2.getPlans().get(i);
                
                if (plan1.getScore() != null && plan2.getScore() != null) {
                    assertEquals(plan1.getScore(), plan2.getScore(), 0.01,
                        String.format("Person %s plan %d should have same score in %s and %s", 
                            personId, i, name1, name2));
                }
            }
        }
    }
    
    @Test
    public void testPopulationWritingWithOffloadedPlans() throws Exception {
        OffloadConfigGroup offloadConfig = new OffloadConfigGroup();
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
        offloadConfig.setStoreDirectory(new File(utils.getOutputDirectory(), "rocksdb-write-test").toString());
        offloadConfig.setCacheEntries(3);
        config.addModule(offloadConfig);
        
        File dbDir = new File(offloadConfig.getStoreDirectory());
        dbDir.mkdirs();
        
        RocksDbPlanStore store = new RocksDbPlanStore(dbDir, scenario, 3);
        
        try {
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
        } finally {
            store.close();
            System.gc();
            Thread.sleep(200);
        }
    }
}
