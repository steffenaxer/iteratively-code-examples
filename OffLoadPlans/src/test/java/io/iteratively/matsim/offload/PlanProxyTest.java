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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PlanProxyTest {

    @TempDir
    File tempDir;

    @Test
    public void testLoadAllPlansAsProxies() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        // Create and persist 3 plans with different scores
        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc)) {
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

            // Verify all plans are loaded as proxies
            List<? extends Plan> plans = person.getPlans();
            assertEquals(3, plans.size(), "Should have 3 plans loaded as proxies");

            // Verify they are all proxies
            for (Plan p : plans) {
                assertTrue(p instanceof PlanProxy, "Plan should be a PlanProxy");
            }

            // Verify scores are accessible without materialization
            PlanProxy proxy0 = (PlanProxy) plans.get(0);
            PlanProxy proxy1 = (PlanProxy) plans.get(1);
            PlanProxy proxy2 = (PlanProxy) plans.get(2);

            assertEquals(10.0, proxy0.getScore(), 0.001);
            assertEquals(11.0, proxy1.getScore(), 0.001);
            assertEquals(12.0, proxy2.getScore(), 0.001);

            // Verify they are NOT materialized yet
            assertFalse(proxy0.isMaterialized(), "Proxy should not be materialized");
            assertFalse(proxy1.isMaterialized(), "Proxy should not be materialized");
            assertFalse(proxy2.isMaterialized(), "Proxy should not be materialized");

            // Verify selected plan
            assertNotNull(person.getSelectedPlan(), "Should have a selected plan");
            assertEquals("p0", ((PlanProxy) person.getSelectedPlan()).getPlanId());
        }
    }

    @Test
    public void testLazyMaterialization() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("1"));

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc)) {
            Plan plan = pf.createPlan();
            plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
            plan.addLeg(pf.createLeg("car"));
            plan.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
            plan.setScore(15.0);
            store.putPlan("1", "p0", plan, 15.0, 0, true);
            store.commit();

            PlanProxy proxy = store.listPlanProxies(person).get(0);

            // Verify not materialized
            assertFalse(proxy.isMaterialized(), "Should not be materialized initially");

            // Access score - should not trigger materialization
            assertEquals(15.0, proxy.getScore(), 0.001);
            assertFalse(proxy.isMaterialized(), "Score access should not materialize");

            // Access plan elements - should trigger materialization
            List<PlanElement> elements = proxy.getPlanElements();
            assertTrue(proxy.isMaterialized(), "Accessing plan elements should materialize");
            assertEquals(3, elements.size(), "Should have 3 plan elements");

            // Test dematerialization
            proxy.dematerialize();
            assertFalse(proxy.isMaterialized(), "Should be dematerialized");

            // Score should still be accessible
            assertEquals(15.0, proxy.getScore(), 0.001);
        }
    }

    @Test
    public void testPersistAllMaterialized() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc)) {
            // Create initial plan
            Plan plan = pf.createPlan();
            plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
            plan.addLeg(pf.createLeg("car"));
            plan.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
            plan.setScore(20.0);
            store.putPlan("1", "p0", plan, 20.0, 0, true);
            store.commit();

            // Load as proxy
            OffloadSupport.loadAllPlansAsProxies(person, store);
            PlanProxy proxy = (PlanProxy) person.getPlans().get(0);

            assertFalse(proxy.isMaterialized(), "Should start not materialized");

            // Materialize and modify
            Plan materialized = proxy.getMaterializedPlan();
            assertTrue(proxy.isMaterialized(), "Should be materialized");

            // Add an activity to modify the plan
            materialized.addActivity(pf.createActivityFromCoord("shop", new Coord(500, 500)));

            // Persist all materialized
            OffloadSupport.persistAllMaterialized(person, store, 1);

            // After persistence, proxy should be dematerialized
            assertFalse(proxy.isMaterialized(), "Should be dematerialized after persist");

            // Score should still be accessible
            assertEquals(20.0, proxy.getScore(), 0.001);

            // Verify the plan was persisted with modifications
            Plan reloaded = store.materialize("1", "p0");
            assertEquals(4, reloaded.getPlanElements().size(), "Should have 4 elements after modification");
        }
    }

    @Test
    public void testSwapSelectedPlanTo() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc)) {
            // Create 2 plans
            for (int i = 0; i < 2; i++) {
                Plan plan = pf.createPlan();
                plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
                plan.addLeg(pf.createLeg("car"));
                plan.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
                plan.setScore(10.0 + i);
                store.putPlan("1", "p" + i, plan, plan.getScore(), 0, i == 0);
            }
            store.commit();

            // Load all as proxies
            OffloadSupport.loadAllPlansAsProxies(person, store);

            // Verify initial state
            assertEquals(2, person.getPlans().size());
            assertEquals("p0", ((PlanProxy) person.getSelectedPlan()).getPlanId());

            // Swap to p1
            OffloadSupport.swapSelectedPlanTo(person, store, "p1");

            // Verify swap worked
            assertEquals(2, person.getPlans().size(), "Should still have 2 plans");
            assertEquals("p1", ((PlanProxy) person.getSelectedPlan()).getPlanId());
        }
    }
}
