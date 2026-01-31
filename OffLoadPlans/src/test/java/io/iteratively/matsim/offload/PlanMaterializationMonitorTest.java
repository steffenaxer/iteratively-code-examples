package io.iteratively.matsim.offload;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PlanMaterializationMonitorTest {

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
        assertEquals(3, stats.get("materializedPlans")); // Regular plans are considered materialized
        assertEquals(0, stats.get("proxyPlans"));
        assertEquals("100.00%", stats.get("materializationRate"));
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
        assertEquals(4, stats.get("materializedPlans"));
        assertEquals(0, stats.get("proxyPlans"));
        assertEquals("100.00%", stats.get("materializationRate"));
    }
}
