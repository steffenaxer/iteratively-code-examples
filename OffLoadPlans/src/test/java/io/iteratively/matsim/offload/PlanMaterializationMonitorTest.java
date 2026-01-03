package io.iteratively.matsim.offload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PlanMaterializationMonitor functionality.
 */
public class PlanMaterializationMonitorTest {

    @TempDir
    File tempDir;

    @Test
    public void testCollectStats_EmptyPopulation() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        
        PlanMaterializationMonitor.MaterializationStats stats = 
                PlanMaterializationMonitor.collectStats(sc.getPopulation());
        
        assertEquals(0, stats.totalPersons());
        assertEquals(0, stats.totalPlans());
        assertEquals(0, stats.materializedPlans());
        assertEquals(0, stats.selectedMaterializedPlans());
        assertEquals(0, stats.nonSelectedMaterializedPlans());
    }

    @Test
    public void testCollectStats_WithProxies() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        
        // Create person with 3 plans
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            // Create and persist 3 plans
            for (int i = 0; i < 3; i++) {
                Plan plan = pf.createPlan();
                plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
                plan.addLeg(pf.createLeg("car"));
                plan.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
                plan.setScore(10.0 + i);
                store.putPlan("1", "p" + i, plan, plan.getScore(), 0, i == 0);
            }
            store.commit();

            // Load all plans as proxies
            OffloadSupport.loadAllPlansAsProxies(person, store);

            // Collect stats before materialization
            PlanMaterializationMonitor.MaterializationStats stats = 
                    PlanMaterializationMonitor.collectStats(sc.getPopulation());

            assertEquals(1, stats.totalPersons());
            assertEquals(3, stats.totalPlans());
            assertEquals(0, stats.materializedPlans(), "No plans should be materialized initially");
            assertEquals(0, stats.selectedMaterializedPlans());
            assertEquals(0, stats.nonSelectedMaterializedPlans());

            // Materialize the selected plan
            Plan selected = person.getSelectedPlan();
            if (selected instanceof PlanProxy proxy) {
                proxy.getMaterializedPlan();
            }

            // Collect stats after materializing selected plan
            stats = PlanMaterializationMonitor.collectStats(sc.getPopulation());

            assertEquals(1, stats.totalPersons());
            assertEquals(3, stats.totalPlans());
            assertEquals(1, stats.materializedPlans(), "Only selected plan should be materialized");
            assertEquals(1, stats.selectedMaterializedPlans());
            assertEquals(0, stats.nonSelectedMaterializedPlans());
        }
    }

    @Test
    public void testDematerializeNonSelected() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            // Create 3 plans
            for (int i = 0; i < 3; i++) {
                Plan plan = pf.createPlan();
                plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
                plan.setScore(10.0 + i);
                store.putPlan("1", "p" + i, plan, plan.getScore(), 0, i == 0);
            }
            store.commit();

            // Load as proxies
            OffloadSupport.loadAllPlansAsProxies(person, store);

            // Materialize all plans
            for (Plan plan : person.getPlans()) {
                if (plan instanceof PlanProxy proxy) {
                    proxy.getMaterializedPlan();
                }
            }

            // Verify all are materialized
            int materialized = 0;
            for (Plan plan : person.getPlans()) {
                if (plan instanceof PlanProxy proxy && proxy.isMaterialized()) {
                    materialized++;
                }
            }
            assertEquals(3, materialized, "All plans should be materialized");

            // Dematerialize non-selected
            int dematerialized = PlanMaterializationMonitor.dematerializeNonSelected(person);
            
            assertEquals(2, dematerialized, "Should have dematerialized 2 non-selected plans");

            // Verify only selected is still materialized
            Plan selected = person.getSelectedPlan();
            for (Plan plan : person.getPlans()) {
                if (plan instanceof PlanProxy proxy) {
                    if (plan == selected) {
                        assertTrue(proxy.isMaterialized(), "Selected plan should remain materialized");
                    } else {
                        assertFalse(proxy.isMaterialized(), "Non-selected plans should be dematerialized");
                    }
                }
            }
        }
    }

    @Test
    public void testDematerializeAllNonSelected() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            // Create 2 persons with 3 plans each
            for (int personIdx = 0; personIdx < 2; personIdx++) {
                Person person = pf.createPerson(Id.createPersonId(String.valueOf(personIdx)));
                sc.getPopulation().addPerson(person);

                for (int planIdx = 0; planIdx < 3; planIdx++) {
                    Plan plan = pf.createPlan();
                    plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
                    plan.setScore(10.0 + planIdx);
                    store.putPlan(String.valueOf(personIdx), "p" + planIdx, plan, 
                            plan.getScore(), 0, planIdx == 0);
                }
            }
            store.commit();

            // Load all as proxies
            for (Person person : sc.getPopulation().getPersons().values()) {
                OffloadSupport.loadAllPlansAsProxies(person, store);
            }

            // Materialize all plans for all persons
            for (Person person : sc.getPopulation().getPersons().values()) {
                for (Plan plan : person.getPlans()) {
                    if (plan instanceof PlanProxy proxy) {
                        proxy.getMaterializedPlan();
                    }
                }
            }

            // Should have 2 persons × 3 plans = 6 materialized plans
            PlanMaterializationMonitor.MaterializationStats stats = 
                    PlanMaterializationMonitor.collectStats(sc.getPopulation());
            assertEquals(6, stats.materializedPlans());

            // Dematerialize all non-selected
            int totalDematerialized = 
                    PlanMaterializationMonitor.dematerializeAllNonSelected(sc.getPopulation());
            
            // Should have dematerialized 2 persons × 2 non-selected plans = 4 plans
            assertEquals(4, totalDematerialized);

            // Verify final state
            stats = PlanMaterializationMonitor.collectStats(sc.getPopulation());
            assertEquals(2, stats.materializedPlans(), "Only 2 selected plans should remain materialized");
            assertEquals(2, stats.selectedMaterializedPlans());
            assertEquals(0, stats.nonSelectedMaterializedPlans());
        }
    }

    @Test
    public void testLogStats() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            Plan plan = pf.createPlan();
            plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
            plan.setScore(10.0);
            store.putPlan("1", "p0", plan, 10.0, 0, true);
            store.commit();

            OffloadSupport.loadAllPlansAsProxies(person, store);

            // This should not throw an exception
            assertDoesNotThrow(() -> {
                PlanMaterializationMonitor.logStats(sc.getPopulation(), "test phase");
            });
        }
    }

    @Test
    public void testStatsDistribution() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            // Person 1: 0 materialized plans
            Person p1 = pf.createPerson(Id.createPersonId("1"));
            sc.getPopulation().addPerson(p1);
            Plan plan1 = pf.createPlan();
            plan1.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
            store.putPlan("1", "p0", plan1, 10.0, 0, true);
            
            // Person 2: 1 materialized plan
            Person p2 = pf.createPerson(Id.createPersonId("2"));
            sc.getPopulation().addPerson(p2);
            Plan plan2 = pf.createPlan();
            plan2.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
            store.putPlan("2", "p0", plan2, 10.0, 0, true);

            store.commit();

            // Load person 1 as proxy (not materialized)
            OffloadSupport.loadAllPlansAsProxies(p1, store);
            
            // Load person 2 as proxy and materialize
            OffloadSupport.loadAllPlansAsProxies(p2, store);
            if (p2.getSelectedPlan() instanceof PlanProxy proxy) {
                proxy.getMaterializedPlan();
            }

            PlanMaterializationMonitor.MaterializationStats stats = 
                    PlanMaterializationMonitor.collectStats(sc.getPopulation());

            assertEquals(2, stats.totalPersons());
            assertEquals(2, stats.totalPlans());
            assertEquals(1, stats.materializedPlans());
            
            // Check distribution
            assertTrue(stats.personsWithNMaterializedPlans().containsKey("0 materialized"));
            assertTrue(stats.personsWithNMaterializedPlans().containsKey("1 materialized"));
            assertEquals(1, stats.personsWithNMaterializedPlans().get("0 materialized"));
            assertEquals(1, stats.personsWithNMaterializedPlans().get("1 materialized"));
        }
    }

    @Test
    public void testMaterializationTimestamp() throws InterruptedException {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            Plan plan = pf.createPlan();
            plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
            plan.setScore(10.0);
            store.putPlan("1", "p0", plan, 10.0, 0, true);
            store.commit();

            OffloadSupport.loadAllPlansAsProxies(person, store);
            PlanProxy proxy = (PlanProxy) person.getSelectedPlan();

            // Verify timestamp is -1 when not materialized
            assertEquals(-1, proxy.getMaterializationTimestamp());
            assertEquals(-1, proxy.getMaterializationDurationMs());

            // Materialize and check timestamp
            long beforeMaterialization = System.currentTimeMillis();
            proxy.getMaterializedPlan();
            long afterMaterialization = System.currentTimeMillis();

            assertTrue(proxy.getMaterializationTimestamp() >= beforeMaterialization);
            assertTrue(proxy.getMaterializationTimestamp() <= afterMaterialization);
            assertTrue(proxy.getMaterializationDurationMs() >= 0);

            // Wait a bit and check duration increases
            Thread.sleep(50);
            long duration1 = proxy.getMaterializationDurationMs();
            assertTrue(duration1 >= 50, "Duration should be at least 50ms");

            Thread.sleep(50);
            long duration2 = proxy.getMaterializationDurationMs();
            assertTrue(duration2 > duration1, "Duration should increase over time");

            // Dematerialize and check timestamp is reset
            proxy.dematerialize();
            assertEquals(-1, proxy.getMaterializationTimestamp());
            assertEquals(-1, proxy.getMaterializationDurationMs());
        }
    }

    @Test
    public void testDematerializeOldNonSelected() throws InterruptedException {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            // Create 3 plans
            for (int i = 0; i < 3; i++) {
                Plan plan = pf.createPlan();
                plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
                plan.setScore(10.0 + i);
                store.putPlan("1", "p" + i, plan, plan.getScore(), 0, i == 0);
            }
            store.commit();

            // Load as proxies
            OffloadSupport.loadAllPlansAsProxies(person, store);

            // Materialize all plans
            for (Plan plan : person.getPlans()) {
                if (plan instanceof PlanProxy proxy) {
                    proxy.getMaterializedPlan();
                }
            }

            // Wait 100ms
            Thread.sleep(100);

            // Dematerialize plans older than 50ms (should dematerialize all 3)
            int dematerialized = PlanMaterializationMonitor.dematerializeOldNonSelected(person, 50);
            
            // Should have dematerialized the 2 non-selected plans (selected plan is protected)
            assertEquals(2, dematerialized);

            // Verify selected is still materialized
            Plan selected = person.getSelectedPlan();
            if (selected instanceof PlanProxy proxy) {
                assertTrue(proxy.isMaterialized());
            }
        }
    }

    @Test
    public void testStatsWithDuration() throws InterruptedException {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            Plan plan = pf.createPlan();
            plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
            plan.setScore(10.0);
            store.putPlan("1", "p0", plan, 10.0, 0, true);
            store.commit();

            OffloadSupport.loadAllPlansAsProxies(person, store);
            PlanProxy proxy = (PlanProxy) person.getSelectedPlan();

            // Materialize and wait
            proxy.getMaterializedPlan();
            Thread.sleep(100);

            // Collect stats
            PlanMaterializationMonitor.MaterializationStats stats = 
                    PlanMaterializationMonitor.collectStats(sc.getPopulation());

            // Verify duration statistics
            assertTrue(stats.maxMaterializationDurationMs() >= 100);
            assertTrue(stats.avgMaterializationDurationMs() >= 100);
        }
    }
}
