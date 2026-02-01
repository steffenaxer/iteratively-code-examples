package io.iteratively.matsim.offload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

public class AfterReplanningDematerializerTest {

    @TempDir
    File tempDir;

    @Test
    public void testDematerializeNonSelectedPlansAfterReplanning() {
        // This test verifies that non-selected plans are dematerialized after replanning
        
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        PopulationFactory factory = scenario.getPopulation().getFactory();
        
        File rocksDbDir = new File(tempDir, "rocksdb");
        rocksDbDir.mkdirs();
        
        try (RocksDbPlanStore store = new RocksDbPlanStore(rocksDbDir, scenario)) {
            // Create a person with 3 proxy plans
            Person person = factory.createPerson(Id.createPersonId("person1"));
            scenario.getPopulation().addPerson(person);
            
            // Store 3 plans
            for (int i = 0; i < 3; i++) {
                Plan plan = factory.createPlan();
                plan.addActivity(factory.createActivityFromCoord("home", new Coord(0, 0)));
                plan.addLeg(factory.createLeg("car"));
                plan.addActivity(factory.createActivityFromCoord("work", new Coord(1000, 500)));
                plan.setScore(10.0 + i);
                plan.getAttributes().putAttribute("offloadPlanId", "plan" + i);
                person.addPlan(plan);
                store.putPlan("person1", "plan" + i, plan, plan.getScore(), 0, i == 0);
            }
            store.commit();
            
            // Load plans as proxies
            OffloadSupport.loadAllPlansAsProxies(person, store);
            
            // Materialize all 3 plans (simulating replanning access)
            PlanProxy proxy0 = (PlanProxy) person.getPlans().get(0);
            PlanProxy proxy1 = (PlanProxy) person.getPlans().get(1);
            PlanProxy proxy2 = (PlanProxy) person.getPlans().get(2);
            
            proxy0.getPlanElements(); // Materialize
            proxy1.getPlanElements(); // Materialize
            proxy2.getPlanElements(); // Materialize
            
            assertTrue(proxy0.isMaterialized(), "Plan 0 should be materialized");
            assertTrue(proxy1.isMaterialized(), "Plan 1 should be materialized");
            assertTrue(proxy2.isMaterialized(), "Plan 2 should be materialized");
            
            // Set plan0 as selected (after replanning)
            person.setSelectedPlan(proxy0);
            
            // Create the dematerializer and trigger AfterMobsim event
            OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
            offloadConfig.setEnableAfterReplanningDematerialization(true);
            
            AfterReplanningDematerializer dematerializer = new AfterReplanningDematerializer(scenario, store);
            
            // Simulate AfterMobsim event (when replanning happens)
            AfterMobsimEvent event = new AfterMobsimEvent(null, 0, false);
            dematerializer.notifyAfterMobsim(event);
            
            // Verify: only selected plan should remain materialized
            assertTrue(proxy0.isMaterialized(), "Selected plan (plan0) should remain materialized");
            assertFalse(proxy1.isMaterialized(), "Non-selected plan (plan1) should be dematerialized");
            assertFalse(proxy2.isMaterialized(), "Non-selected plan (plan2) should be dematerialized");
        }
    }

    @Test
    public void testConfigGroupDefaultValue() {
        OffloadConfigGroup config = new OffloadConfigGroup();
        assertTrue(config.getEnableAfterReplanningDematerialization(), 
                   "After replanning dematerialization should be enabled by default");
    }

    @Test
    public void testConfigGroupSetter() {
        OffloadConfigGroup config = new OffloadConfigGroup();
        
        // Start with default (true)
        assertTrue(config.getEnableAfterReplanningDematerialization());
        
        // Disable
        config.setEnableAfterReplanningDematerialization(false);
        assertFalse(config.getEnableAfterReplanningDematerialization());
        
        // Re-enable
        config.setEnableAfterReplanningDematerialization(true);
        assertTrue(config.getEnableAfterReplanningDematerialization());
    }
}
