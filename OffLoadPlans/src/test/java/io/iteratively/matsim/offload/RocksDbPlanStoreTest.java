package io.iteratively.matsim.offload;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RocksDbPlanStoreTest {
    
    @TempDir
    Path tempDir;
    
    private Scenario scenario;
    private RocksDbPlanStore store;
    
    @BeforeEach
    public void setUp() {
        scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        File dbDir = tempDir.resolve("rocksdb").toFile();
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
        
        List<PlanHeader> headers = store.listPlanHeaders("person1");
        assertEquals(3, headers.size());
        
        for (PlanHeader header : headers) {
            assertEquals(0, header.creationIter, "All plans should have creationIter = 0");
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
        
        List<PlanHeader> headers = store.listPlanHeaders("person1");
        assertEquals(1, headers.size());
        assertEquals(0, headers.get(0).creationIter, "creationIter should remain 0 (from first put)");
        assertEquals(1, headers.get(0).lastUsedIter, "lastUsedIter should be 1 (from second put)");
    }
    
    @Test
    public void testListPlanHeaders() {
        PopulationFactory pf = scenario.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("person1"));
        scenario.getPopulation().addPerson(person);
        
        Plan plan = pf.createPlan();
        plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
        plan.setScore(15.5);
        person.addPlan(plan);
        
        store.putPlan("person1", "plan1", plan, 15.5, 5, true);
        
        List<PlanHeader> headers = store.listPlanHeaders("person1");
        
        assertEquals(1, headers.size());
        PlanHeader header = headers.get(0);
        assertEquals("plan1", header.planId);
        assertEquals(15.5, header.score, 0.001);
        assertEquals(5, header.creationIter);
        assertEquals(5, header.lastUsedIter);
        assertTrue(header.selected);
    }
}
