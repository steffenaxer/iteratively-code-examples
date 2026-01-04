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
 * Test to verify that when MATSim strategies copy plans (e.g., during replanning),
 * the newly created regular Plan objects are converted to PlanProxy objects
 * after persistence to maintain memory efficiency.
 * 
 * This addresses the issue where createCopyOfSelectedPlanAndMakeSelected() creates
 * a regular Plan object that the store doesn't know about, keeping full plans in memory.
 */
public class PlanCopyingTest {

    @TempDir
    File tempDir;

    @Test
    public void testRegularPlanConvertedToProxyAfterPersistence() {
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

            // Simulate what MATSim's replanning does: create a copy as a regular Plan
            Plan selectedPlan = person.getSelectedPlan();
            Plan copiedPlan = pf.createPlan();
            PopulationUtils.copyFromTo(selectedPlan, copiedPlan);
            copiedPlan.setPerson(person);
            copiedPlan.setPlanMutator("TimeAllocationMutator"); // Strategy sets this
            
            // Add the copied plan directly (like MATSim does)
            person.addPlan(copiedPlan);
            person.setSelectedPlan(copiedPlan);
            
            assertEquals(2, person.getPlans().size(), "Should have 2 plans after copying");
            assertTrue(person.getPlans().get(0) instanceof PlanProxy, "First plan should be proxy");
            assertFalse(person.getPlans().get(1) instanceof PlanProxy, "Copied plan should be regular Plan");

            // Now persist all materialized plans - this should convert the regular plan to a proxy
            OffloadSupport.persistAllMaterialized(person, store, 1);
            store.commit();

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
            assertEquals(3, reloaded.getPlanElements().size(), "Should have correct number of elements");
            assertEquals("TimeAllocationMutator", reloaded.getPlanMutator(), 
                "Reloaded plan should have planMutator");
        }
    }

    @Test
    public void testMultipleRegularPlansConvertedToProxies() {
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

            // Add multiple regular plans (simulating multiple strategy applications)
            for (int i = 0; i < 3; i++) {
                Plan newPlan = pf.createPlan();
                newPlan.addActivity(pf.createActivityFromCoord("home", new Coord(0, 0)));
                newPlan.addLeg(pf.createLeg("car"));
                newPlan.addActivity(pf.createActivityFromCoord("work", new Coord(1000, 500)));
                newPlan.setScore(10.0 + i);
                newPlan.setPerson(person);
                newPlan.setPlanMutator("Strategy" + i);
                person.addPlan(newPlan);
            }

            assertEquals(4, person.getPlans().size(), "Should have 4 plans");
            int regularPlanCount = 0;
            for (Plan plan : person.getPlans()) {
                if (!(plan instanceof PlanProxy)) {
                    regularPlanCount++;
                }
            }
            assertEquals(3, regularPlanCount, "Should have 3 regular plans");

            // Persist all - should convert all regular plans to proxies
            OffloadSupport.persistAllMaterialized(person, store, 1);
            store.commit();

            // Verify all are now proxies
            assertEquals(4, person.getPlans().size(), "Should still have 4 plans");
            for (Plan plan : person.getPlans()) {
                assertTrue(plan instanceof PlanProxy, "All plans should be proxies after persistence");
            }

            // Verify each proxy has the correct planMutator
            for (int i = 1; i <= 3; i++) {
                PlanProxy proxy = (PlanProxy) person.getPlans().get(i);
                assertEquals("Strategy" + (i-1), proxy.getPlanMutator(), 
                    "Proxy " + i + " should have correct planMutator");
            }
        }
    }
}
