
package io.iteratively.matsim.offload;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

public class FuryRoundtripTest {
    @Test
    public void roundtrip() throws Exception {
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        PopulationFactory pf = sc.getPopulation().getFactory();
        Person person = pf.createPerson(Id.createPersonId("1"));
        sc.getPopulation().addPerson(person);

        Plan plan = pf.createPlan();
        plan.addActivity(pf.createActivityFromCoord("home", new Coord(0,0)));
        plan.addLeg(pf.createLeg("car"));
        plan.addActivity(pf.createActivityFromCoord("work", new Coord(1000,500)));
        plan.setScore(12.34);
        person.addPlan(plan);
        person.setSelectedPlan(plan);

        File db = new File("target/plans.mapdb");
        MapDbPlanStore store = new MapDbPlanStore(db, sc, sc.getConfig().replanning().getMaxAgentPlanMemorySize());
        store.putPlan("1", "p0", plan, plan.getScore(), 0, true);

        var headers = store.listPlanHeaders("1");
        assertEquals(1, headers.size());
        assertEquals(12.34, headers.get(0).score, 1e-9);

        Plan copy = store.materialize("1", "p0");
        assertNotNull(copy);
        assertEquals(plan.getPlanElements().size(), copy.getPlanElements().size());

        store.close();
    }
}
