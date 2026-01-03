package io.iteratively.matsim.offload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.ReplanningEvent;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that the ReplanningConversionListener correctly converts
 * regular Plan objects to PlanProxy objects immediately after replanning.
 */
public class ReplanningConversionListenerTest {

    @TempDir
    File tempDir;

    @Test
    public void testConvertRegularPlansAfterReplanning() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        MapDbPlanStore store = new MapDbPlanStore(db, sc, 5);
        PlanCache cache = new PlanCache(store, 10);
        
        try {
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

            // Create a simple mock ReplanningEvent
            ReplanningEvent event = new ReplanningEvent() {
                @Override
                public int getIteration() {
                    return 1;
                }

                @Override
                public org.matsim.core.controler.MatsimServices getServices() {
                    return new org.matsim.core.controler.MatsimServices() {
                        @Override
                        public Scenario getScenario() {
                            return sc;
                        }

                        // Other methods can throw UnsupportedOperationException as they won't be called
                        @Override
                        public org.matsim.core.config.Config getConfig() {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public org.matsim.core.events.EventsManager getEvents() {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public com.google.inject.Injector getInjector() {
                            throw new UnsupportedOperationException();
                        }
                    };
                }
            };
            
            // Create the listener and trigger it
            ReplanningConversionListener listener = new ReplanningConversionListener(store, cache);
            listener.notifyReplanning(event);

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
            
            // Verify the selected proxy is materialized (ready for use)
            assertTrue(copiedProxy.isMaterialized(), 
                "Selected plan proxy should be materialized after conversion");
            
        } finally {
            store.close();
        }
    }

    @Test
    public void testNoConversionWhenAllPlansAreProxies() {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        File db = new File(tempDir, "plans.mapdb");
        MapDbPlanStore store = new MapDbPlanStore(db, sc, 5);
        PlanCache cache = new PlanCache(store, 10);
        
        try {
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

            // Create a simple mock ReplanningEvent
            ReplanningEvent event = new ReplanningEvent() {
                @Override
                public int getIteration() {
                    return 1;
                }

                @Override
                public org.matsim.core.controler.MatsimServices getServices() {
                    return new org.matsim.core.controler.MatsimServices() {
                        @Override
                        public Scenario getScenario() {
                            return sc;
                        }

                        @Override
                        public org.matsim.core.config.Config getConfig() {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public org.matsim.core.events.EventsManager getEvents() {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public com.google.inject.Injector getInjector() {
                            throw new UnsupportedOperationException();
                        }
                    };
                }
            };
            
            // Create the listener and trigger it
            ReplanningConversionListener listener = new ReplanningConversionListener(store, cache);
            listener.notifyReplanning(event);

            // Verify all plans are still proxies and count hasn't changed
            assertEquals(3, person.getPlans().size(), "Should still have 3 plans");
            for (Plan plan : person.getPlans()) {
                assertTrue(plan instanceof PlanProxy, "All plans should still be proxies");
            }
            
        } finally {
            store.close();
        }
    }
}
