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

import static org.junit.jupiter.api.Assertions.*;

public class MobsimPlanMaterializationMonitorTest {

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
    }

    @Test
    public void testConfigGroupSetters() {
        OffloadConfigGroup config = new OffloadConfigGroup();
        
        config.setEnableMobsimMonitoring(false);
        assertFalse(config.getEnableMobsimMonitoring());
        
        config.setMobsimMonitoringIntervalSeconds(1200.0);
        assertEquals(1200.0, config.getMobsimMonitoringIntervalSeconds(), 0.001);
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
}
