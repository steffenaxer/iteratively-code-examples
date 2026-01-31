package io.iteratively.matsim.offload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PlanMaterializationMonitorTest {

    @TempDir
    File tempDir;

    @Test
    public void testCollectStatsWithEmptyPopulation() {
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        Population population = scenario.getPopulation();

        Map<String, Object> stats = PlanMaterializationMonitor.collectStats(population);

        assertEquals(0, stats.get("totalPersons"));
        assertEquals(0, stats.get("totalPlans"));
        assertEquals(0, stats.get("materializedPlans"));
        assertEquals(0, stats.get("proxyPlans"));
        assertEquals(0, stats.get("regularPlans"));
        assertEquals("0.00%", stats.get("materializationRate"));
    }

    @Test
    public void testCollectStatsWithRegularPlans() {
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        Population population = scenario.getPopulation();
        PopulationFactory factory = population.getFactory();

        // Create 3 persons with regular plans
        for (int i = 0; i < 3; i++) {
            Person person = factory.createPerson(Id.createPersonId("person" + i));
            Plan plan = factory.createPlan();
            person.addPlan(plan);
            person.setSelectedPlan(plan);
            population.addPerson(person);
        }

        Map<String, Object> stats = PlanMaterializationMonitor.collectStats(population);

        assertEquals(3, stats.get("totalPersons"));
        assertEquals(3, stats.get("totalPlans"));
        assertEquals(0, stats.get("materializedPlans")); // No proxy plans materialized
        assertEquals(0, stats.get("proxyPlans")); // No proxy plans
        assertEquals(3, stats.get("regularPlans")); // All plans are regular
        assertEquals("0.00%", stats.get("materializationRate")); // 0% since no proxy plans
    }

    @Test
    public void testCollectStatsWithMultiplePlansPerPerson() {
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        Population population = scenario.getPopulation();
        PopulationFactory factory = population.getFactory();

        // Create 2 persons with 2 plans each
        for (int i = 0; i < 2; i++) {
            Person person = factory.createPerson(Id.createPersonId("person" + i));
            for (int j = 0; j < 2; j++) {
                Plan plan = factory.createPlan();
                person.addPlan(plan);
            }
            person.setSelectedPlan(person.getPlans().get(0));
            population.addPerson(person);
        }

        Map<String, Object> stats = PlanMaterializationMonitor.collectStats(population);

        assertEquals(2, stats.get("totalPersons"));
        assertEquals(4, stats.get("totalPlans"));
        assertEquals(0, stats.get("materializedPlans")); // No proxy plans materialized
        assertEquals(0, stats.get("proxyPlans")); // No proxy plans
        assertEquals(4, stats.get("regularPlans")); // All plans are regular
        assertEquals("0.00%", stats.get("materializationRate")); // 0% since no proxy plans
    }

    @Test
    public void testCollectStatsWithMixedPlansAndProxies() {
        // This test verifies the fix for the bug where materialization rate was > 100%
        // The bug occurred when regular plans were counted as materialized but not as proxies
        
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Population population = scenario.getPopulation();
        PopulationFactory factory = population.getFactory();
        
        File db = new File(tempDir, "test-plans.mapdb");
        
        try (MapDbPlanStore store = new MapDbPlanStore(db, scenario)) {
            // Create 2 persons:
            // Person 1: 2 regular plans
            Person person1 = factory.createPerson(Id.createPersonId("person1"));
            Plan regularPlan1 = factory.createPlan();
            regularPlan1.addActivity(factory.createActivityFromCoord("home", new Coord(0, 0)));
            person1.addPlan(regularPlan1);
            Plan regularPlan2 = factory.createPlan();
            regularPlan2.addActivity(factory.createActivityFromCoord("work", new Coord(100, 100)));
            person1.addPlan(regularPlan2);
            person1.setSelectedPlan(regularPlan1);
            population.addPerson(person1);
            
            // Person 2: 3 proxy plans (2 materialized, 1 not materialized)
            Person person2 = factory.createPerson(Id.createPersonId("person2"));
            population.addPerson(person2);
            
            // Store 3 plans for person2
            for (int i = 0; i < 3; i++) {
                Plan plan = factory.createPlan();
                plan.addActivity(factory.createActivityFromCoord("home", new Coord(0, 0)));
                plan.addLeg(factory.createLeg("car"));
                plan.addActivity(factory.createActivityFromCoord("work", new Coord(1000, 500)));
                plan.setScore(10.0 + i);
                store.putPlan("person2", "plan" + i, plan, plan.getScore(), 0, i == 0);
            }
            store.commit();
            
            // Load plans as proxies
            OffloadSupport.loadAllPlansAsProxies(person2, store);
            
            // Materialize 2 of the 3 proxy plans
            PlanProxy proxy0 = (PlanProxy) person2.getPlans().get(0);
            PlanProxy proxy1 = (PlanProxy) person2.getPlans().get(1);
            PlanProxy proxy2 = (PlanProxy) person2.getPlans().get(2);
            
            proxy0.getPlanElements(); // Force materialization
            proxy1.getPlanElements(); // Force materialization
            // proxy2 remains not materialized
            
            assertTrue(proxy0.isMaterialized(), "Proxy 0 should be materialized");
            assertTrue(proxy1.isMaterialized(), "Proxy 1 should be materialized");
            assertFalse(proxy2.isMaterialized(), "Proxy 2 should NOT be materialized");
            
            // Collect stats
            Map<String, Object> stats = PlanMaterializationMonitor.collectStats(population);
            
            // Verify counts
            assertEquals(2, stats.get("totalPersons"), "Should have 2 persons");
            assertEquals(5, stats.get("totalPlans"), "Should have 5 total plans (2 regular + 3 proxy)");
            assertEquals(2, stats.get("regularPlans"), "Should have 2 regular plans");
            assertEquals(3, stats.get("proxyPlans"), "Should have 3 proxy plans");
            assertEquals(2, stats.get("materializedPlans"), "Should have 2 materialized proxy plans");
            
            // The key test: materialization rate should be 2/3 = 66.67%, NOT > 100%
            String rateStr = (String) stats.get("materializationRate");
            assertEquals("66.67%", rateStr, "Materialization rate should be 66.67%");
        }
    }
}
