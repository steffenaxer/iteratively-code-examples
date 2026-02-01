package io.iteratively.matsim.offload;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RocksDbPlanStoreTest {
    
    @RegisterExtension
    private MatsimTestUtils utils = new MatsimTestUtils();
    
    private Scenario scenario;
    private RocksDbPlanStore store;
    
    @BeforeEach
    public void setUp() {
        scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        File dbDir = new File(utils.getOutputDirectory(), "rocksdb");
        dbDir.mkdirs();
        store = new RocksDbPlanStore(dbDir, scenario);
    }
    
    @AfterEach
    public void tearDown() {
        if (store != null) {
            store.close();
            store = null;
        }
        System.gc();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @Test
    public void testPutAndMaterializeWithoutFlush() {
        PopulationFactory pf = scenario.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("person1"));
        scenario.getPopulation().addPerson(person);
        
        Plan plan = pf.createPlan();
        plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
        plan.addLeg(pf.createLeg("car"));
        plan.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
        plan.setScore(12.34);
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        
        store.putPlan("person1", "plan1", plan, 12.34, 0, true);
        
        Plan materialized = store.materialize("person1", "plan1");
        
        assertNotNull(materialized, "Plan should be retrievable immediately after put, even before flush");
        assertEquals(3, materialized.getPlanElements().size());
    }
    
    @Test
    public void testPutAndMaterializeWithFlush() {
        PopulationFactory pf = scenario.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("person1"));
        scenario.getPopulation().addPerson(person);
        
        Plan plan = pf.createPlan();
        plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
        plan.addLeg(pf.createLeg("car"));
        plan.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
        plan.setScore(12.34);
        plan.getAttributes().putAttribute("offloadPlanId", "plan1");
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        
        store.putPlan("person1", "plan1", plan, 12.34, 0, true);
        store.commit();
        
        Plan materialized = store.materialize("person1", "plan1");
        
        assertNotNull(materialized, "Plan should be retrievable after flush");
        assertEquals(3, materialized.getPlanElements().size());
    }
    
    @Test
    public void testMultiplePlansWithSameCreationIter() {
        PopulationFactory pf = scenario.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("person1"));
        scenario.getPopulation().addPerson(person);
        
        for (int i = 0; i < 3; i++) {
            Plan plan = pf.createPlan();
            plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
            plan.addLeg(pf.createLeg("car"));
            plan.addActivity(pf.createActivityFromCoord("work", new Coord(i * 1000, i * 500)));
            plan.setScore(10.0 + i);
            person.addPlan(plan);
            
            store.putPlan("person1", "plan" + i, plan, 10.0 + i, 0, i == 0);
        }
        
        for (int i = 0; i < 3; i++) {
            Plan materialized = store.materialize("person1", "plan" + i);
            assertNotNull(materialized, "Plan " + i + " should be retrievable");
        }
        
        List<PlanProxy> proxies = store.listPlanProxies(person);
        assertEquals(3, proxies.size());
        
        for (PlanProxy proxy : proxies) {
            assertEquals(0, proxy.getIterationCreated(), "All plans should have creationIter = 0");
        }
    }
    
    @Test
    public void testUpdateExistingPlan() {
        PopulationFactory pf = scenario.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("person1"));
        scenario.getPopulation().addPerson(person);
        
        Plan plan1 = pf.createPlan();
        plan1.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
        plan1.setScore(10.0);
        person.addPlan(plan1);
        
        store.putPlan("person1", "plan1", plan1, 10.0, 0, true);
        
        Plan plan2 = pf.createPlan();
        plan2.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
        plan2.setScore(20.0);
        
        store.putPlan("person1", "plan1", plan2, 20.0, 1, true);
        
        Plan materialized = store.materialize("person1", "plan1");
        assertNotNull(materialized);
        assertEquals(1, materialized.getPlanElements().size());
        
        Person testPerson = pf.createPerson(Id.createPersonId("person1"));
        List<PlanProxy> proxies = store.listPlanProxies(testPerson);
        assertEquals(1, proxies.size());
        assertEquals(0, proxies.get(0).getIterationCreated(), "creationIter should remain 0 (from first put)");
    }
    
    @Test
    public void testListPlanProxies() {
        PopulationFactory pf = scenario.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("person1"));
        scenario.getPopulation().addPerson(person);
        
        Plan plan = pf.createPlan();
        plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
        plan.setScore(15.5);
        person.addPlan(plan);
        
        store.putPlan("person1", "plan1", plan, 15.5, 5, true);
        
        List<PlanProxy> proxies = store.listPlanProxies(person);
        
        assertEquals(1, proxies.size());
        PlanProxy proxy = proxies.get(0);
        assertEquals("plan1", proxy.getPlanId());
        assertEquals(15.5, proxy.getScore(), 0.001);
        assertEquals(5, proxy.getIterationCreated());
    }
    
    @Test
    public void testStoreSynchronizesWithPersonPlanList() {
        // This test validates that the store synchronizes with Person's plan list
        // The Person's plan list is the single source of truth
        PopulationFactory pf = scenario.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("person1"));
        scenario.getPopulation().addPerson(person);
        
        // Create and store 5 plans
        for (int i = 0; i < 5; i++) {
            Plan plan = pf.createPlan();
            plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
            plan.setScore(10.0 + i);
            plan.getAttributes().putAttribute("offloadPlanId", "plan" + i);
            person.addPlan(plan);
            
            store.putPlan("person1", "plan" + i, plan, 10.0 + i, 0, i == 0);
        }
        
        // Verify all 5 plans are stored
        assertEquals(5, store.listPlanIds("person1").size(), "Should have 5 plans in store");
        
        // Load plans as proxies
        OffloadSupport.loadAllPlansAsProxies(person, store);
        assertEquals(5, person.getPlans().size(), "Should have 5 plans in Person");
        
        // Now remove 2 plans from the Person (simulating MATSim's plan selection/removal)
        person.getPlans().remove(4); // Remove plan4
        person.getPlans().remove(3); // Remove plan3
        assertEquals(3, person.getPlans().size(), "Should have 3 plans in Person after removal");
        
        // Commit should synchronize store with Person - removing orphaned plans
        store.commit();
        
        // Store should now only have the 3 plans that are in Person
        List<String> remainingIds = store.listPlanIds("person1");
        assertEquals(3, remainingIds.size(), "Store should have 3 plans after synchronization");
        
        // Verify the correct plans remain (plan0, plan1, plan2)
        assertTrue(remainingIds.contains("plan0"), "plan0 should remain");
        assertTrue(remainingIds.contains("plan1"), "plan1 should remain");
        assertTrue(remainingIds.contains("plan2"), "plan2 should remain");
        assertFalse(remainingIds.contains("plan3"), "plan3 should be removed");
        assertFalse(remainingIds.contains("plan4"), "plan4 should be removed");
    }
}
