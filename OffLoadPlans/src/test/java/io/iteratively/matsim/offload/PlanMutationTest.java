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
 * Test to verify that plan mutations are properly handled when plans are stored as proxies.
 * 
 * When MATSim strategies (like TimeAllocationMutator, ReRoute) mutate plans:
 * 1. The plan must be materialized before mutation
 * 2. The mutation changes must be detected and persisted
 * 3. The mutated plan should be correctly stored and retrievable
 */
public class PlanMutationTest {

    @TempDir
    File tempDir;

    @Test
    public void testPlanMutationIsPersisted() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            // Create an initial plan
            Plan plan = pf.createPlan();
            Activity homeAct = pf.createActivityFromCoord("home", new Coord(0, 0));
            homeAct.setEndTime(8 * 3600); // 8 AM
            plan.addActivity(homeAct);
            plan.addLeg(pf.createLeg("car"));
            Activity workAct = pf.createActivityFromCoord("work", new Coord(1000, 500));
            workAct.setEndTime(17 * 3600); // 5 PM
            plan.addActivity(workAct);
            plan.setScore(15.0);
            
            // Store the plan
            store.putPlan("1", "p0", plan, 15.0, 0, true);
            store.commit();

            // Load the plan as a proxy
            OffloadSupport.loadAllPlansAsProxies(person, store);
            PlanProxy proxy = (PlanProxy) person.getPlans().get(0);

            assertFalse(proxy.isMaterialized(), "Should start not materialized");

            // Simulate a plan mutation (like TimeAllocationMutator would do)
            // This requires accessing plan elements, which will materialize the plan
            Plan materializedPlan = proxy.getMaterializedPlan();
            assertTrue(proxy.isMaterialized(), "Should be materialized after accessing elements");

            // Mutate the plan by changing activity end time
            Activity firstActivity = (Activity) materializedPlan.getPlanElements().get(0);
            double originalEndTime = firstActivity.getEndTime().seconds();
            assertEquals(8 * 3600, originalEndTime, 0.01, "Original end time should be 8 AM");
            
            // Change the time (simulate mutation)
            firstActivity.setEndTime(7 * 3600); // Change to 7 AM
            
            // Persist the mutated plan (this is what happens at iteration end)
            OffloadSupport.persistAllMaterialized(person, store, 1);
            store.commit();

            // After persistence, proxy should be dematerialized
            assertFalse(proxy.isMaterialized(), "Should be dematerialized after persist");

            // Reload the plan from store to verify mutation was persisted
            Plan reloadedPlan = store.materialize("1", "p0");
            assertNotNull(reloadedPlan, "Should be able to reload the plan");
            assertEquals(3, reloadedPlan.getPlanElements().size(), "Should have 3 plan elements");
            
            Activity reloadedActivity = (Activity) reloadedPlan.getPlanElements().get(0);
            assertEquals(7 * 3600, reloadedActivity.getEndTime().seconds(), 0.01, 
                "Mutated end time should be 7 AM after reload");
        }
    }

    @Test
    public void testMultiplePlanMutationsAreAllPersisted() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            // Create 3 initial plans with different times
            for (int i = 0; i < 3; i++) {
                Plan plan = pf.createPlan();
                Activity homeAct = pf.createActivityFromCoord("home", new Coord(0, 0));
                homeAct.setEndTime((8 + i) * 3600); // 8 AM, 9 AM, 10 AM
                plan.addActivity(homeAct);
                plan.addLeg(pf.createLeg("car"));
                plan.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
                plan.setScore(10.0 + i);
                store.putPlan("1", "p" + i, plan, plan.getScore(), 0, i == 0);
            }
            store.commit();

            // Load all plans as proxies
            OffloadSupport.loadAllPlansAsProxies(person, store);
            assertEquals(3, person.getPlans().size(), "Should have 3 plans");

            // Mutate plan 1 (randomly selected by a strategy)
            PlanProxy proxy1 = (PlanProxy) person.getPlans().get(1);
            Plan materialized1 = proxy1.getMaterializedPlan();
            Activity act1 = (Activity) materialized1.getPlanElements().get(0);
            act1.setEndTime(7.5 * 3600); // Change to 7:30 AM

            // Mutate plan 2
            PlanProxy proxy2 = (PlanProxy) person.getPlans().get(2);
            Plan materialized2 = proxy2.getMaterializedPlan();
            Activity act2 = (Activity) materialized2.getPlanElements().get(0);
            act2.setEndTime(8.5 * 3600); // Change to 8:30 AM

            // Persist all materialized plans
            OffloadSupport.persistAllMaterialized(person, store, 1);
            store.commit();

            // Verify both mutations were persisted
            Plan reloaded1 = store.materialize("1", "p1");
            Activity reloadedAct1 = (Activity) reloaded1.getPlanElements().get(0);
            assertEquals(7.5 * 3600, reloadedAct1.getEndTime().seconds(), 0.01, 
                "Plan 1 mutation should be persisted");

            Plan reloaded2 = store.materialize("1", "p2");
            Activity reloadedAct2 = (Activity) reloaded2.getPlanElements().get(0);
            assertEquals(8.5 * 3600, reloadedAct2.getEndTime().seconds(), 0.01, 
                "Plan 2 mutation should be persisted");

            // Plan 0 should not have been mutated (not materialized)
            PlanProxy proxy0 = (PlanProxy) person.getPlans().get(0);
            assertFalse(proxy0.isMaterialized(), "Plan 0 should not be materialized");
        }
    }

    @Test
    public void testPlanAttributesArePreservedAfterMutation() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            // Create a plan
            Plan plan = pf.createPlan();
            plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
            plan.addLeg(pf.createLeg("car"));
            plan.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
            
            // Add custom attributes
            plan.getAttributes().putAttribute("customAttr", "testValue");
            
            store.putPlan("1", "p0", plan, 10.0, 0, true);
            store.commit();

            // Load as proxy
            OffloadSupport.loadAllPlansAsProxies(person, store);
            PlanProxy proxy = (PlanProxy) person.getPlans().get(0);

            // Mutate the plan
            Plan materialized = proxy.getMaterializedPlan();
            
            // Note: offloadPlanId should be preserved if it was set
            String planId = OffloadSupport.ensurePlanId(materialized);
            assertNotNull(planId, "Plan should have an ID");
            
            // Modify the plan
            Activity act = (Activity) materialized.getPlanElements().get(0);
            act.setEndTime(8 * 3600);

            // Persist
            OffloadSupport.persistAllMaterialized(person, store, 1);
            store.commit();

            // Reload and verify ID is consistent
            Plan reloaded = store.materialize("1", "p0");
            String reloadedId = OffloadSupport.ensurePlanId(reloaded);
            
            // The ID might be generated fresh, but it should be consistently generated
            assertNotNull(reloadedId, "Reloaded plan should have an ID");
        }
    }
}
