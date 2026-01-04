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
        store = new RocksDbPlanStore(dbDir, scenario, 5);
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
    public void testPlanLimitEnforcementUsesPersonPlanList() {
        // This test validates the refactored plan limit enforcement
        // which now uses the Person's actual plan list instead of cache
        PopulationFactory pf = scenario.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("person1"));
        scenario.getPopulation().addPerson(person);
        
        // Create 8 plans (maxPlansPerAgent is 5)
        for (int i = 0; i < 8; i++) {
            Plan plan = pf.createPlan();
            plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
            plan.setScore(10.0 + i); // Scores: 10, 11, 12, 13, 14, 15, 16, 17
            plan.getAttributes().putAttribute("offloadPlanId", "plan" + i);
            person.addPlan(plan);
            
            store.putPlan("person1", "plan" + i, plan, 10.0 + i, 0, i == 0);
        }
        
        // Load plans as proxies
        OffloadSupport.loadAllPlansAsProxies(person, store);
        
        // Verify all 8 plans are loaded
        assertEquals(8, person.getPlans().size(), "Should have 8 plans before commit");
        
        // Commit triggers plan limit enforcement
        store.commit();
        
        // After commit, reload proxies to see what's left in store
        person.getPlans().clear();
        OffloadSupport.loadAllPlansAsProxies(person, store);
        
        // Should have only 5 plans left (maxPlansPerAgent)
        assertEquals(5, person.getPlans().size(), "Should have only 5 plans after enforcement");
        
        // The 3 lowest-scoring plans (10, 11, 12) should be deleted
        // The remaining plans should be 13, 14, 15, 16, 17
        List<Double> scores = person.getPlans().stream()
            .map(Plan::getScore)
            .sorted()
            .toList();
        
        assertEquals(13.0, scores.get(0), 0.001, "Lowest remaining score should be 13");
        assertEquals(17.0, scores.get(4), 0.001, "Highest score should be 17");
    }
}
