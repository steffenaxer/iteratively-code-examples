package io.iteratively.matsim.offload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that regular Plan objects are correctly converted to PlanProxy objects
 * when they are created during replanning (e.g., by createCopyOfSelectedPlanAndMakeSelected).
 * 
 * This tests the core convertRegularPlansToProxies() method that is used by both
 * persistAllMaterialized() and ReplanningConversionListener.
 */
public class ReplanningConversionListenerTest {

    @TempDir
    File tempDir;

    @Test
    public void testConvertRegularPlansToProxies() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            // Create and persist an initial plan as proxy
            Plan initialPlan = pf.createPlan();
            initialPlan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
            initialPlan.addLeg(pf.createLeg("car"));
            initialPlan.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
            initialPlan.setScore(15.0);
            
            store.putPlan("1", "p0", initialPlan, 15.0, 0, true);
            store.commit();

            // Load as proxy
            OffloadSupport.loadAllPlansAsProxies(person, store);
            assertEquals(1, person.getPlans().size(), "Should have 1 plan");
            assertTrue(person.getPlans().get(0) instanceof PlanProxy, "Plan should be a proxy");

            // Simulate what happens during replanning: create a copy as a regular Plan
            Plan selectedPlan = person.getSelectedPlan();
            Plan copiedPlan = pf.createPlan();
            PopulationUtils.copyFromTo(selectedPlan, copiedPlan);
            copiedPlan.setPerson(person);
            copiedPlan.setPlanMutator("TimeAllocationMutator");
            
            // Add the copied plan using proper API (like MATSim does during replanning)
            person.addPlan(copiedPlan);
            person.setSelectedPlan(copiedPlan);
            
            assertEquals(2, person.getPlans().size(), "Should have 2 plans");
            assertTrue(person.getPlans().get(0) instanceof PlanProxy, "First plan should be proxy");
            assertFalse(person.getPlans().get(1) instanceof PlanProxy, "Copied plan should be regular Plan");

            // Persist the regular plan first (as ReplanningConversionListener does)
            String planId = OffloadSupport.ensurePlanId(copiedPlan);
            double score = OffloadSupport.toStorableScore(copiedPlan.getScore());
            boolean isSelected = (copiedPlan == person.getSelectedPlan());
            store.putPlan("1", planId, copiedPlan, score, 1, isSelected);
            store.commit();
            
            // Now convert regular plans to proxies (core method being tested)
            int conversions = OffloadSupport.convertRegularPlansToProxies(person, store, 1);
            
            assertEquals(1, conversions, "Should have converted 1 plan");

            // Verify that the regular plan was converted to a proxy
            assertEquals(2, person.getPlans().size(), "Should still have 2 plans");
            assertTrue(person.getPlans().get(0) instanceof PlanProxy, "First plan should still be proxy");
            assertTrue(person.getPlans().get(1) instanceof PlanProxy, "Copied plan should now be a proxy!");

            // Verify the selected plan is the proxy
            assertTrue(person.getSelectedPlan() instanceof PlanProxy, "Selected plan should be a proxy");

            // Verify the proxy has the correct planMutator
            PlanProxy copiedProxy = (PlanProxy) person.getPlans().get(1);
            assertEquals("TimeAllocationMutator", copiedProxy.getPlanMutator(), 
                "Proxy should preserve planMutator");
            
            // Verify we can reload from store
            Plan reloaded = store.materialize("1", copiedProxy.getPlanId());
            assertNotNull(reloaded, "Should be able to reload the plan");
            assertEquals("TimeAllocationMutator", reloaded.getPlanMutator(), 
                "Reloaded plan should preserve planMutator");
        }
    }

    @Test
    public void testConvertMultipleRegularPlans() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            // Start with one proxy plan
            Plan initialPlan = pf.createPlan();
            initialPlan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
            initialPlan.addLeg(pf.createLeg("car"));
            initialPlan.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
            
            store.putPlan("1", "p0", initialPlan, 10.0, 0, true);
            store.commit();
            OffloadSupport.loadAllPlansAsProxies(person, store);

            // Add multiple regular plans (simulating multiple replanning operations)
            for (int i = 1; i <= 3; i++) {
                Plan newPlan = pf.createPlan();
                newPlan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
                newPlan.addLeg(pf.createLeg("car"));
                newPlan.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
                newPlan.setScore(10.0 + i);
                newPlan.setPerson(person);
                newPlan.setPlanMutator("Strategy" + i);
                person.addPlan(newPlan);
                
                // Persist each regular plan
                String planId = OffloadSupport.ensurePlanId(newPlan);
                store.putPlan("1", planId, newPlan, newPlan.getScore(), 1, false);
            }
            store.commit();

            assertEquals(4, person.getPlans().size(), "Should have 4 plans");
            
            // Count regular plans before conversion
            int regularCount = 0;
            for (Plan plan : person.getPlans()) {
                if (!(plan instanceof PlanProxy)) {
                    regularCount++;
                }
            }
            assertEquals(3, regularCount, "Should have 3 regular plans");

            // Convert all regular plans to proxies
            int conversions = OffloadSupport.convertRegularPlansToProxies(person, store, 1);
            
            assertEquals(3, conversions, "Should have converted 3 plans");

            // Verify all are now proxies
            assertEquals(4, person.getPlans().size(), "Should still have 4 plans");
            for (Plan plan : person.getPlans()) {
                assertTrue(plan instanceof PlanProxy, "All plans should be proxies after conversion");
            }

            // Verify each proxy has the correct planMutator
            for (int i = 1; i <= 3; i++) {
                PlanProxy proxy = (PlanProxy) person.getPlans().get(i);
                assertEquals("Strategy" + i, proxy.getPlanMutator(), 
                    "Proxy " + i + " should have correct planMutator");
            }
        }
    }

    @Test
    public void testNoConversionWhenAllPlansAreProxies() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        try (MapDbPlanStore store = new MapDbPlanStore(db, sc, 5)) {
            // Create initial plans
            for (int i = 0; i < 3; i++) {
                Plan plan = pf.createPlan();
                plan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
                plan.addLeg(pf.createLeg("car"));
                plan.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
                store.putPlan("1", "p" + i, plan, 10.0 + i, 0, i == 0);
            }
            store.commit();

            // Load as proxies
            OffloadSupport.loadAllPlansAsProxies(person, store);
            assertEquals(3, person.getPlans().size(), "Should have 3 plans");
            
            // Verify all are proxies
            for (Plan plan : person.getPlans()) {
                assertTrue(plan instanceof PlanProxy, "All plans should be proxies");
            }

            // Try to convert - should do nothing since all are already proxies
            int conversions = OffloadSupport.convertRegularPlansToProxies(person, store, 1);
            
            assertEquals(0, conversions, "Should have converted 0 plans");

            // Verify all plans are still proxies and count hasn't changed
            assertEquals(3, person.getPlans().size(), "Should still have 3 plans");
            for (Plan plan : person.getPlans()) {
                assertTrue(plan instanceof PlanProxy, "All plans should still be proxies");
            }
        }
    }
}
