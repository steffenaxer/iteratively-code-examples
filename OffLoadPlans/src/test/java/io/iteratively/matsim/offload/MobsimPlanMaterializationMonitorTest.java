package io.iteratively.matsim.offload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MobSim plan materialization monitoring.
 */
public class MobsimPlanMaterializationMonitorTest {

    @RegisterExtension private MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    public void testMobsimMonitoringEnabled() {
        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("equil");
        Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(scenarioUrl, "config.xml"));

        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.MAPDB);
        offloadConfig.setEnableMobsimMonitoring(true);
        offloadConfig.setMobsimMonitoringIntervalSeconds(60.0); // Monitor every minute
        offloadConfig.setLogMaterializationStats(true);

        config.controller().setOutputDirectory(utils.getOutputDirectory());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(1);
        config.replanning().setMaxAgentPlanMemorySize(3);

        // Use streaming approach to load scenario
        Scenario scenario = OffloadSupport.loadScenarioWithStreaming(config);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());
        
        // Should not throw exception
        assertDoesNotThrow(() -> controler.run());
    }

    @Test
    public void testMobsimMonitoringDisabled() {
        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("equil");
        Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(scenarioUrl, "config.xml"));

        OffloadConfigGroup offloadConfig = ConfigUtils.addOrGetModule(config, OffloadConfigGroup.class);
        offloadConfig.setStorageBackend(OffloadConfigGroup.StorageBackend.MAPDB);
        offloadConfig.setEnableMobsimMonitoring(false); // Disable monitoring

        config.controller().setOutputDirectory(utils.getOutputDirectory());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(1);
        config.replanning().setMaxAgentPlanMemorySize(3);

        Scenario scenario = OffloadSupport.loadScenarioWithStreaming(config);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule());
        
        // Should not throw exception even with monitoring disabled
        assertDoesNotThrow(() -> controler.run());
    }

    @Test
    public void testConfigurationParameters() {
        OffloadConfigGroup config = new OffloadConfigGroup();
        
        // Test default values
        assertTrue(config.isEnableMobsimMonitoring(), 
                "MobSim monitoring should be enabled by default");
        assertEquals(300.0, config.getMobsimMonitoringIntervalSeconds(), 0.01,
                "Default monitoring interval should be 300 seconds");
        
        // Test setters
        config.setEnableMobsimMonitoring(false);
        assertFalse(config.isEnableMobsimMonitoring());
        
        config.setMobsimMonitoringIntervalSeconds(600.0);
        assertEquals(600.0, config.getMobsimMonitoringIntervalSeconds(), 0.01);
        
        // Test string setters/getters (for XML config)
        config.setEnableMobsimMonitoringFromString("true");
        assertTrue(config.isEnableMobsimMonitoring());
        assertEquals("true", config.getEnableMobsimMonitoringAsString());
        
        config.setMobsimMonitoringIntervalSecondsFromString("120.5");
        assertEquals(120.5, config.getMobsimMonitoringIntervalSeconds(), 0.01);
        assertEquals("120.5", config.getMobsimMonitoringIntervalSecondsAsString());
    }
}
