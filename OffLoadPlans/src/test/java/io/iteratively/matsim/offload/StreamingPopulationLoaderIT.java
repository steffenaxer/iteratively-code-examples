package io.iteratively.matsim.offload;

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
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for streaming population loading functionality.
 * 
 * <p>Tests that the streaming population loader correctly loads populations
 * into the plan store without loading all plans into memory at once.</p>
 */
public class StreamingPopulationLoaderIT {
    
    @RegisterExtension
    private MatsimTestUtils utils = new MatsimTestUtils();
    
    private Config config;
    private Scenario scenario;
    private File populationFile;
    
    @BeforeEach
    public void setUp() {
        config = ConfigUtils.createConfig();
        
        config.controller().setFirstIteration(0);
        config.controller().setLastIteration(2);
        config.controller().setOutputDirectory(utils.getOutputDirectory());
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setWriteEventsInterval(0);
        config.controller().setWritePlansInterval(2);
        config.controller().setCreateGraphs(false);
        
        ScoringConfigGroup.ActivityParams homeAct = new ScoringConfigGroup.ActivityParams("home");
        homeAct.setTypicalDuration(12 * 3600);
        config.scoring().addActivityParams(homeAct);
        
        ScoringConfigGroup.ActivityParams workAct = new ScoringConfigGroup.ActivityParams("work");
        workAct.setTypicalDuration(8 * 3600);
        config.scoring().addActivityParams(workAct);
        
        ReplanningConfigGroup.StrategySettings strategySettings1 = new ReplanningConfigGroup.StrategySettings();
        strategySettings1.setStrategyName("ChangeExpBeta");
        strategySettings1.setWeight(0.8);
        config.replanning().addStrategySettings(strategySettings1);
        
        ReplanningConfigGroup.StrategySettings strategySettings2 = new ReplanningConfigGroup.StrategySettings();
        strategySettings2.setStrategyName("ReRoute");
        strategySettings2.setWeight(0.2);
        config.replanning().addStrategySettings(strategySettings2);
        
        config.replanning().setMaxAgentPlanMemorySize(5);
        
        scenario = ScenarioUtils.createScenario(config);
        createSimpleNetwork();
    }
    
    private void createSimpleNetwork() {
        Network network = scenario.getNetwork();
        NetworkFactory nf = network.getFactory();
        
        Node node1 = nf.createNode(Id.createNodeId("1"), new Coord(0, 0));
        Node node2 = nf.createNode(Id.createNodeId("2"), new Coord(1000, 0));
        Node node3 = nf.createNode(Id.createNodeId("3"), new Coord(1000, 1000));
        
        network.addNode(node1);
        network.addNode(node2);
        network.addNode(node3);
        
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
        
        Link link31 = nf.createLink(Id.createLinkId("3-1"), node3, node1);
        link31.setLength(1000);
        link31.setFreespeed(50.0 / 3.6);
        link31.setCapacity(2000);
        link31.setNumberOfLanes(1);
        network.addLink(link31);
    }
    
    private void createAndWritePopulation(int personCount, int plansPerPerson, File outputFile) {
        Scenario tempScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Population population = tempScenario.getPopulation();
        PopulationFactory pf = population.getFactory();
        
        for (int i = 0; i < personCount; i++) {
            Person person = pf.createPerson(Id.createPersonId("person_" + i));
            
            for (int planIdx = 0; planIdx < plansPerPerson; planIdx++) {
                Plan plan = pf.createPlan();
                
                Activity homeAct1 = pf.createActivityFromLinkId("home", Id.createLinkId("1-2"));
                homeAct1.setEndTime(7 * 3600 + i * 60 + planIdx * 300);
                plan.addActivity(homeAct1);
                
                plan.addLeg(pf.createLeg("car"));
                
                Activity workAct = pf.createActivityFromLinkId("work", Id.createLinkId("2-3"));
                workAct.setEndTime(17 * 3600 + i * 60 + planIdx * 300);
                plan.addActivity(workAct);
                
                plan.addLeg(pf.createLeg("car"));
                
                Activity homeAct2 = pf.createActivityFromLinkId("home", Id.createLinkId("1-2"));
                plan.addActivity(homeAct2);
                
                // Set score for non-selected plans
                if (planIdx > 0) {
                    plan.setScore(100.0 - planIdx * 10.0);
                }
                
                person.addPlan(plan);
                
                if (planIdx == 0) {
                    person.setSelectedPlan(plan);
                }
            }
            
            population.addPerson(person);
        }
        
        new PopulationWriter(population).write(outputFile.getAbsolutePath());
        populationFile = outputFile;  // Update the global variable for tests that need it
    }
    
    @Test
    public void testStreamingPopulationLoader() throws Exception {
        // Create a population file with multiple plans per person
        createAndWritePopulation(10, 4, new File(utils.getOutputDirectory(), "input_population.xml.gz"));
        
        // Setup offload configuration
        OffloadConfigGroup offloadConfig = new OffloadConfigGroup();
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
        offloadConfig.setStoreDirectory(new File(utils.getOutputDirectory(), "store").toString());
        offloadConfig.setCacheEntries(5);
        config.addModule(offloadConfig);
        
        // Create plan store
        File storeDir = new File(offloadConfig.getStoreDirectory(), "rocksdb");
        storeDir.mkdirs();
        RocksDbPlanStore planStore = new RocksDbPlanStore(storeDir, scenario, 5);
        
        try {
            // Use streaming loader
            StreamingPopulationLoader loader = new StreamingPopulationLoader(
                planStore, scenario, 0);
            loader.loadFromFile(populationFile.getAbsolutePath());
            
            // Verify population was loaded
            assertEquals(10, scenario.getPopulation().getPersons().size(),
                "All persons should be in population");
            
            // Verify plans are in the store
            for (Person person : scenario.getPopulation().getPersons().values()) {
                String personId = person.getId().toString();
                
                // Check that all plans are in the store
                assertEquals(4, planStore.listPlanIds(personId).size(),
                    "All plans should be in store for person " + personId);
                
                // Check that person has no plans yet (they will be loaded as proxies later)
                assertEquals(0, person.getPlans().size(),
                    "Person should have no plans in memory yet");
                
                // Verify active plan is set correctly
                assertTrue(planStore.getActivePlanId(personId).isPresent(),
                    "Active plan should be set for person " + personId);
            }
            
            // Now load plans as proxies
            for (Person person : scenario.getPopulation().getPersons().values()) {
                OffloadSupport.loadAllPlansAsProxies(person, planStore);
                
                assertEquals(4, person.getPlans().size(),
                    "Person should have all plans as proxies");
                
                // Verify selected plan is a proxy
                assertNotNull(person.getSelectedPlan(), "Person should have selected plan");
                assertTrue(person.getSelectedPlan() instanceof PlanProxy,
                    "Selected plan should be a proxy");
            }
            
        } finally {
            planStore.close();
        }
    }
    
    @Test
    public void testStreamingModuleIntegration() {
        // Create population file OUTSIDE the output directory (parent directory)
        File popFileDir = new File(utils.getOutputDirectory()).getParentFile();
        File popFile = new File(popFileDir, "testStreamingModuleIntegration_population.xml.gz");
        
        createAndWritePopulation(5, 3, popFile);
        
        // Setup new scenario without loading population
        Scenario runScenario = ScenarioUtils.createScenario(config);
        
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
        
        // Setup offload configuration
        OffloadConfigGroup offloadConfig = new OffloadConfigGroup();
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
        offloadConfig.setStoreDirectory(new File(config.controller().getOutputDirectory(), "store").toString());
        offloadConfig.setCacheEntries(5);
        config.addModule(offloadConfig);
        
        // Create controller with streaming module
        Controler controler = new Controler(runScenario);
        controler.addOverridingModule(new OffloadModule());
        controler.addOverridingModule(new StreamingOffloadModule(popFile.getAbsolutePath()));
        
        controler.run();
        
        // Verify output
        File outputPlansFile = new File(config.controller().getOutputDirectory(),
            "output_plans.xml.gz");
        assertTrue(outputPlansFile.exists(), "Output plans file should exist");
        
        // Read and verify output population
        Scenario outputScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(outputScenario).readFile(outputPlansFile.getAbsolutePath());
        
        assertEquals(5, outputScenario.getPopulation().getPersons().size(),
            "Output should have all persons");
        
        for (Person person : outputScenario.getPopulation().getPersons().values()) {
            assertTrue(person.getPlans().size() >= 3,
                "Each person should have at least the initial 3 plans");
            assertNotNull(person.getSelectedPlan(), "Each person should have a selected plan");
        }
    }
    
    @Test
    public void testCompareStreamingVsNonStreaming() {
        // Create population file OUTSIDE the output directory (parent directory)
        File popFileDir = new File(utils.getOutputDirectory()).getParentFile();
        File popFile = new File(popFileDir, "testCompareStreamingVsNonStreaming_population.xml.gz");
        
        createAndWritePopulation(5, 3, popFile);
        
        // Run with streaming
        Scenario streamingResult = runWithStreaming(popFile.getAbsolutePath());
        
        // Run without streaming (traditional approach)
        Scenario traditionalResult = runTraditional(popFile.getAbsolutePath());
        
        // Compare results
        assertEquals(streamingResult.getPopulation().getPersons().size(),
            traditionalResult.getPopulation().getPersons().size(),
            "Both approaches should have same number of persons");
        
        for (Id<Person> personId : streamingResult.getPopulation().getPersons().keySet()) {
            Person streamingPerson = streamingResult.getPopulation().getPersons().get(personId);
            Person traditionalPerson = traditionalResult.getPopulation().getPersons().get(personId);
            
            assertNotNull(traditionalPerson, "Person should exist in both results");
            
            assertEquals(streamingPerson.getPlans().size(),
                traditionalPerson.getPlans().size(),
                "Person should have same number of plans in both approaches");
            
            // Compare selected plan scores
            if (streamingPerson.getSelectedPlan().getScore() != null &&
                traditionalPerson.getSelectedPlan().getScore() != null) {
                assertEquals(streamingPerson.getSelectedPlan().getScore(),
                    traditionalPerson.getSelectedPlan().getScore(), 0.01,
                    "Selected plan scores should match");
            }
        }
    }
    
    private Scenario runWithStreaming(String popFile) {
        Config runConfig = createRunConfig("streaming");
        Scenario runScenario = ScenarioUtils.createScenario(runConfig);
        copyNetwork(runScenario);
        
        OffloadConfigGroup offloadConfig = new OffloadConfigGroup();
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
        offloadConfig.setStoreDirectory(
            new File(runConfig.controller().getOutputDirectory(), "store").toString());
        offloadConfig.setCacheEntries(5);
        runConfig.addModule(offloadConfig);
        
        Controler controler = new Controler(runScenario);
        controler.addOverridingModule(new OffloadModule());
        controler.addOverridingModule(new StreamingOffloadModule(popFile));
        controler.run();
        
        return readOutputScenario(runConfig);
    }
    
    private Scenario runTraditional(String popFile) {
        Config runConfig = createRunConfig("traditional");
        Scenario runScenario = ScenarioUtils.createScenario(runConfig);
        copyNetwork(runScenario);
        
        // Load population traditionally
        new PopulationReader(runScenario).readFile(popFile);
        
        OffloadConfigGroup offloadConfig = new OffloadConfigGroup();
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
        offloadConfig.setStoreDirectory(
            new File(runConfig.controller().getOutputDirectory(), "store").toString());
        offloadConfig.setCacheEntries(5);
        runConfig.addModule(offloadConfig);
        
        Controler controler = new Controler(runScenario);
        controler.addOverridingModule(new OffloadModule());
        controler.run();
        
        return readOutputScenario(runConfig);
    }
    
    private Config createRunConfig(String subfolder) {
        Config runConfig = ConfigUtils.createConfig();
        runConfig.controller().setFirstIteration(0);
        runConfig.controller().setLastIteration(2);
        runConfig.controller().setOutputDirectory(
            new File(utils.getOutputDirectory(), subfolder).toString());
        runConfig.controller().setOverwriteFileSetting(
            OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        runConfig.controller().setWriteEventsInterval(0);
        runConfig.controller().setWritePlansInterval(2);
        runConfig.controller().setCreateGraphs(false);
        
        ScoringConfigGroup.ActivityParams homeAct = new ScoringConfigGroup.ActivityParams("home");
        homeAct.setTypicalDuration(12 * 3600);
        runConfig.scoring().addActivityParams(homeAct);
        
        ScoringConfigGroup.ActivityParams workAct = new ScoringConfigGroup.ActivityParams("work");
        workAct.setTypicalDuration(8 * 3600);
        runConfig.scoring().addActivityParams(workAct);
        
        ReplanningConfigGroup.StrategySettings strategySettings1 = new ReplanningConfigGroup.StrategySettings();
        strategySettings1.setStrategyName("ChangeExpBeta");
        strategySettings1.setWeight(0.8);
        runConfig.replanning().addStrategySettings(strategySettings1);
        
        ReplanningConfigGroup.StrategySettings strategySettings2 = new ReplanningConfigGroup.StrategySettings();
        strategySettings2.setStrategyName("ReRoute");
        strategySettings2.setWeight(0.2);
        runConfig.replanning().addStrategySettings(strategySettings2);
        
        runConfig.replanning().setMaxAgentPlanMemorySize(5);
        
        return runConfig;
    }
    
    private void copyNetwork(Scenario target) {
        Network network = target.getNetwork();
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
    }
    
    private Scenario readOutputScenario(Config runConfig) {
        File outputPlansFile = new File(runConfig.controller().getOutputDirectory(),
            "output_plans.xml.gz");
        Scenario outputScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(outputScenario).readFile(outputPlansFile.getAbsolutePath());
        return outputScenario;
    }
}
