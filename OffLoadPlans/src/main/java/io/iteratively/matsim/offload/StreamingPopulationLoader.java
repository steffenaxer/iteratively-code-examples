package io.iteratively.matsim.offload;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.population.algorithms.PersonAlgorithm;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Streaming population loader that reads a population file and loads all plans
 * except the selected plan into the plan store.
 * 
 * <p>This loader uses MATSim's {@link StreamingPopulationReader} to read persons
 * one at a time, ensuring that not all plans are in memory simultaneously. Only
 * the selected plan of each person is added to the population in the scenario,
 * while all other plans are stored in the plan store.</p>
 * 
 * <p>This approach is memory-efficient and allows for large populations to be
 * loaded without exceeding memory limits.</p>
 */
public class StreamingPopulationLoader {
    private static final Logger log = LogManager.getLogger(StreamingPopulationLoader.class);
    
    private final PlanStore planStore;
    private final Scenario targetScenario;
    private final int initialIteration;
    
    /**
     * Creates a new streaming population loader.
     * 
     * @param planStore the plan store to load non-selected plans into
     * @param targetScenario the scenario containing the population to be populated
     * @param initialIteration the initial iteration number (typically 0)
     */
    public StreamingPopulationLoader(PlanStore planStore, Scenario targetScenario, int initialIteration) {
        this.planStore = planStore;
        this.targetScenario = targetScenario;
        this.initialIteration = initialIteration;
    }
    
    /**
     * Loads a population from a file using streaming.
     * 
     * <p>This method reads the population file and for each person:
     * <ul>
     *   <li>Stores all plans (including selected) in the plan store</li>
     *   <li>Adds person to the target scenario population (without plans)</li>
     * </ul>
     * 
     * @param filename the path to the population file to load
     */
    public void loadFromFile(String filename) {
        log.info("Starting streaming population load from: {}", filename);
        
        // Create a temporary scenario for reading
        Scenario tempScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        
        StreamingPopulationReader reader = new StreamingPopulationReader(tempScenario);
        reader.addAlgorithm(new PopulationLoadingAlgorithm());
        reader.readFile(filename);
        
        planStore.commit();
        log.info("Streaming population load completed. {} persons in population.",
            targetScenario.getPopulation().getPersons().size());
    }
    
    /**
     * Algorithm that processes each person during streaming population reading.
     * Stores all plans in the plan store and adds person (without plans) to target population.
     */
    private class PopulationLoadingAlgorithm implements PersonAlgorithm {
        private int personCount = 0;
        
        @Override
        public void run(Person person) {
            personCount++;
            
            if (personCount % 10000 == 0) {
                log.info("Processed {} persons", personCount);
            }
            
            String personId = person.getId().toString();
            Plan selectedPlan = person.getSelectedPlan();
            
            // Store all plans in the plan store
            for (Plan plan : person.getPlans()) {
                String planId = OffloadSupport.ensurePlanId(plan);
                double score = OffloadSupport.toStorableScore(plan.getScore());
                boolean isSelected = (plan == selectedPlan);
                
                planStore.putPlan(personId, planId, plan, score, initialIteration, isSelected);
            }
            
            // Add person to target scenario population without plans
            // (plans will be loaded as proxies later)
            Person scenarioPerson = targetScenario.getPopulation().getFactory().createPerson(person.getId());
            
            // Copy person attributes
            person.getAttributes().getAsMap().forEach(((key,o) ->
                scenarioPerson.getAttributes().putAttribute(key, o)));
            
            targetScenario.getPopulation().addPerson(scenarioPerson);
        }
    }
}
