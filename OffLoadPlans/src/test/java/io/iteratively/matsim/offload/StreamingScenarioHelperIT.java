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
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the new automatic streaming approach using StreamingScenarioHelper.
 */
public class StreamingScenarioHelperIT {
    
    @RegisterExtension
    private MatsimTestUtils utils = new MatsimTestUtils();
    
    private File populationFile;
    
    @BeforeEach
    public void setUp() {
        // Create a simple test population file
        createTestPopulation();
    }
    
    private void createTestPopulation() {
        Config tempConfig = ConfigUtils.createConfig();
        Scenario tempScenario = ScenarioUtils.createScenario(tempConfig);
        
        // Create simple network
        Network network = tempScenario.getNetwork();
        NetworkFactory nf = network.getFactory();
        
        Node node1 = nf.createNode(Id.createNodeId("1"), new Coord(0, 0));
        Node node2 = nf.createNode(Id.createNodeId("2"), new Coord(1000, 0));
        network.addNode(node1);
        network.addNode(node2);
        
        Link link = nf.createLink(Id.createLinkId("1-2"), node1, node2);
        link.setLength(1000);
        link.setFreespeed(50.0 / 3.6);
        link.setCapacity(2000);
        link.setNumberOfLanes(1);
        network.addLink(link);
        
        // Create population with multiple plans
        Population population = tempScenario.getPopulation();
        PopulationFactory pf = population.getFactory();
        
        for (int i = 0; i < 5; i++) {
            Person person = pf.createPerson(Id.createPersonId("person_" + i));
            
            for (int planIdx = 0; planIdx < 3; planIdx++) {
                Plan plan = pf.createPlan();
                
                Activity act1 = pf.createActivityFromLinkId("home", Id.createLinkId("1-2"));
                act1.setEndTime(7 * 3600 + i * 60 + planIdx * 300);
                plan.addActivity(act1);
                
                plan.addLeg(pf.createLeg("car"));
                
                Activity act2 = pf.createActivityFromLinkId("work", Id.createLinkId("1-2"));
                plan.addActivity(act2);
                
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
        
        // Write population file
        File popFileDir = new File(utils.getOutputDirectory()).getParentFile();
        populationFile = new File(popFileDir, "test_population.xml.gz");
        new PopulationWriter(population).write(populationFile.getAbsolutePath());
    }
    
    @Test
    public void testStreamingScenarioHelper() {
        // Create config with population file
        Config config = ConfigUtils.createConfig();
        
        // Set population file in config
        config.plans().setInputFile(populationFile.getAbsolutePath());
        
        // Configure offload
        OffloadConfigGroup offloadConfig = new OffloadConfigGroup();
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
        config.addModule(offloadConfig);
        
        config.replanning().setMaxAgentPlanMemorySize(5);
        config.controller().setFirstIteration(0);
        config.controller().setOutputDirectory(utils.getOutputDirectory());
        
        // Load scenario with streaming - this should load all plans into store
        // and selected plans into scenario
        Scenario scenario = OffloadSupport.loadScenarioWithStreaming(config);
        
        // Verify population was loaded
        assertEquals(5, scenario.getPopulation().getPersons().size(),
            "Population should have 5 persons after streaming load");
        
        // Verify each person has plans as proxies
        for (Person person : scenario.getPopulation().getPersons().values()) {
            assertEquals(3, person.getPlans().size(),
                "Each person should have 3 plans as proxies in memory");
            assertNotNull(person.getSelectedPlan(),
                "Each person should have a selected plan");
            assertTrue(person.getSelectedPlan() instanceof PlanProxy,
                "Selected plan should be a proxy");
            
            // Verify selected plan is materialized
            PlanProxy selectedProxy = (PlanProxy) person.getSelectedPlan();
            assertTrue(selectedProxy.isMaterialized(),
                "Selected plan should be materialized");
        }
        
        // Verify config was restored
        assertEquals(populationFile.getAbsolutePath(), config.plans().getInputFile(),
            "Config population file should be restored");
    }
    
    @Test
    public void testStreamingScenarioHelperWithoutPopulationFile() {
        // Create config without population file
        Config config = ConfigUtils.createConfig();
        
        // Configure offload
        OffloadConfigGroup offloadConfig = new OffloadConfigGroup();
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.ROCKSDB);
        config.addModule(offloadConfig);
        
        // Should throw exception when no population file is specified
        assertThrows(IllegalArgumentException.class, () -> {
            OffloadSupport.loadScenarioWithStreaming(config);
        }, "Should throw exception when no population file is specified");
    }
}
