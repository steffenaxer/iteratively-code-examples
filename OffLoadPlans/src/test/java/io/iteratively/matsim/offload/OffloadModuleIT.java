package io.iteratively.matsim.offload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class OffloadModuleIT {

    @TempDir
    Path tempDir;

    @Test
    public void testOffloadWithSiouxfalls() {
        // Sioux Falls Szenario laden
        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
        Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(scenarioUrl, "config_default.xml"));
        
        // Testspezifische Konfiguration
        config.controller().setOutputDirectory(tempDir.resolve("output").toString());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(2);
        
        Scenario scenario = ScenarioUtils.loadScenario(config);
        
        // Controler mit OffloadModule starten
        File dbFile = tempDir.resolve("plans.mapdb").toFile();
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule(dbFile, 100));
        
        // Simulation ausführen
        controler.run();
        
        // Assertions
        assertTrue(dbFile.exists(), "MapDB Datei sollte existiert");
        assertTrue(dbFile.length() > 0, "MapDB Datei sollte Daten enthalten");
        
        // Verifizieren dass Pläne gespeichert wurden
        try (MapDbPlanStore store = new MapDbPlanStore(dbFile, scenario)) {
            int storedPlans = 0;
            for (Person person : scenario.getPopulation().getPersons().values()) {
                var headers = store.listPlanHeaders(person.getId().toString());
                storedPlans += headers.size();
            }
            assertTrue(storedPlans > 0, "Es sollten Pläne im Store gespeichert sein");
        }
    }

    @Test
    public void testPlanMaterializationAfterSimulation() {
        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
        Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(scenarioUrl, "config_default.xml"));
        
        config.controller().setOutputDirectory(tempDir.resolve("output2").toString());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(1);
        
        Scenario scenario = ScenarioUtils.loadScenario(config);
        File dbFile = tempDir.resolve("plans2.mapdb").toFile();
        
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new OffloadModule(dbFile, 50));
        controler.run();
        
        // Pläne nach Simulation materialisieren und prüfen
        try (MapDbPlanStore store = new MapDbPlanStore(dbFile, scenario)) {
            String firstPersonId = scenario.getPopulation().getPersons().keySet()
                    .iterator().next().toString();
            
            var headers = store.listPlanHeaders(firstPersonId);
            assertFalse(headers.isEmpty(), "Person sollte mindestens einen Plan haben");
            
            var header = headers.get(0);
            var plan = store.materialize(firstPersonId, header.planId);
            
            assertNotNull(plan, "Materialisierter Plan sollte nicht null sein");
            assertFalse(plan.getPlanElements().isEmpty(), 
                    "Plan sollte Elemente enthalten");
        }
    }
}
