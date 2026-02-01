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

import static org.junit.jupiter.api.Assertions.*;

public class MobsimPlanMaterializationMonitorTest {

    @TempDir
    File tempDir;

    @Test
    public void testMonitorCreation() {
        Config config = ConfigUtils.createConfig();
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        offloadConfig.setEnableMobsimMonitoring(true);

        Scenario scenario = ScenarioUtils.createScenario(config);
        
        // Should not throw any exception
        MobsimPlanMaterializationMonitor monitor = new MobsimPlanMaterializationMonitor(scenario);
        assertNotNull(monitor);
    }

    @Test
    public void testMonitorCreationWithDisabledMonitoring() {
        Config config = ConfigUtils.createConfig();
        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        offloadConfig.setEnableMobsimMonitoring(false);

        Scenario scenario = ScenarioUtils.createScenario(config);
        
        // Should not throw any exception even when disabled
        MobsimPlanMaterializationMonitor monitor = new MobsimPlanMaterializationMonitor(scenario);
        assertNotNull(monitor);
    }

    @Test
    public void testConfigGroupDefaults() {
        OffloadConfigGroup config = new OffloadConfigGroup();
        
        assertTrue(config.getEnableMobsimMonitoring(), "Monitoring should be enabled by default");
        assertEquals(3600.0, config.getMobsimMonitoringIntervalSeconds(), 0.001, "Default interval should be 3600.0");
        assertTrue(config.getEnableMobsimDematerialization(), "Dematerialization should be enabled by default");
    }

    @Test
    public void testConfigGroupSetters() {
        OffloadConfigGroup config = new OffloadConfigGroup();
        
        config.setEnableMobsimMonitoring(false);
        assertFalse(config.getEnableMobsimMonitoring());
        
        config.setMobsimMonitoringIntervalSeconds(1200.0);
        assertEquals(1200.0, config.getMobsimMonitoringIntervalSeconds(), 0.001);
        
        config.setEnableMobsimDematerialization(false);
        assertFalse(config.getEnableMobsimDematerialization());
    }

    @Test
    public void testCustomMonitoringInterval() {
        OffloadConfigGroup config = new OffloadConfigGroup();
        config.setMobsimMonitoringIntervalSeconds(1800.0); // 30 minutes
        
        assertEquals(1800.0, config.getMobsimMonitoringIntervalSeconds(), 0.001);
    }

    @Test
    public void testMonitoringToggle() {
        OffloadConfigGroup config = new OffloadConfigGroup();
        
        // Start with default (true)
        assertTrue(config.getEnableMobsimMonitoring());
        
        // Disable
        config.setEnableMobsimMonitoring(false);
        assertFalse(config.getEnableMobsimMonitoring());
        
        // Re-enable
        config.setEnableMobsimMonitoring(true);
        assertTrue(config.getEnableMobsimMonitoring());
    }

    @Test
    public void testDematerializationToggle() {
        OffloadConfigGroup config = new OffloadConfigGroup();
        
        // Start with default (true)
        assertTrue(config.getEnableMobsimDematerialization());
        
        // Disable
        config.setEnableMobsimDematerialization(false);
        assertFalse(config.getEnableMobsimDematerialization());
        
        // Re-enable
        config.setEnableMobsimDematerialization(true);
        assertTrue(config.getEnableMobsimDematerialization());
    }

    @Test
    public void testDematerializeNonSelectedPlans() {
        // This test verifies that non-selected plans are dematerialized
        // while the selected plan remains materialized
        
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        Population population = scenario.getPopulation();
        PopulationFactory factory = population.getFactory();
        
        File rocksDbDir = new File(tempDir, "test-rocksdb");
        rocksDbDir.mkdirs();
        
        try (RocksDbPlanStore store = new RocksDbPlanStore(rocksDbDir, scenario)) {
            // Create a person with 3 proxy plans
            Person person = factory.createPerson(Id.createPersonId("person1"));
            population.addPerson(person);
            
            // Store 3 plans
            for (int i = 0; i < 3; i++) {
                Plan plan = factory.createPlan();
                plan.addActivity(factory.createActivityFromCoord("home", new Coord(0, 0)));
                plan.addLeg(factory.createLeg("car"));
                plan.addActivity(factory.createActivityFromCoord("work", new Coord(1000, 500)));
                plan.setScore(10.0 + i);
                store.putPlan("person1", "plan" + i, plan, plan.getScore(), 0, i == 0);
            }
            store.commit();
            
            // Load plans as proxies
            OffloadSupport.loadAllPlansAsProxies(person, store);
            
            // Materialize all 3 plans (simulating access during simulation)
            PlanProxy proxy0 = (PlanProxy) person.getPlans().get(0);
            PlanProxy proxy1 = (PlanProxy) person.getPlans().get(1);
            PlanProxy proxy2 = (PlanProxy) person.getPlans().get(2);
            
            proxy0.getPlanElements(); // Materialize
            proxy1.getPlanElements(); // Materialize
            proxy2.getPlanElements(); // Materialize
            
            assertTrue(proxy0.isMaterialized(), "Plan 0 should be materialized");
            assertTrue(proxy1.isMaterialized(), "Plan 1 should be materialized");
            assertTrue(proxy2.isMaterialized(), "Plan 2 should be materialized");
            
            // Set plan0 as selected
            person.setSelectedPlan(proxy0);
            
            // Now create a monitor and manually invoke dematerialization
            OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
            offloadConfig.setEnableMobsimDematerialization(true);
            
            MobsimPlanMaterializationMonitor monitor = new MobsimPlanMaterializationMonitor(scenario);
            
            // Use reflection to call the private method (for testing)
            // In real usage, this is called automatically during monitoring
            try {
                var method = MobsimPlanMaterializationMonitor.class.getDeclaredMethod("dematerializeNonSelectedPlans");
                method.setAccessible(true);
                method.invoke(monitor);
            } catch (Exception e) {
                fail("Failed to invoke dematerializeNonSelectedPlans: " + e.getMessage());
            }
            
            // Verify: only selected plan should remain materialized
            assertTrue(proxy0.isMaterialized(), "Selected plan (plan0) should remain materialized");
            assertFalse(proxy1.isMaterialized(), "Non-selected plan (plan1) should be dematerialized");
            assertFalse(proxy2.isMaterialized(), "Non-selected plan (plan2) should be dematerialized");
        }
    }
}
