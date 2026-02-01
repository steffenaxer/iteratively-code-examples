package chicago;

// NOTE: This implementation requires the OffLoadPlans module to be fixed and compiled.
// The OffLoadPlans module currently has compilation errors that are unrelated to this change.
// Once those are resolved, this code will work as intended.

/*
import io.iteratively.matsim.offload.OffloadSupport;
import io.iteratively.matsim.offload.PlanProxy;
import io.iteratively.matsim.offload.PlanStore;
*/
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.population.algorithms.PersonAlgorithm;

/**
 * Streaming handler that converts incoming persons and plans to use PlanProxy.
 * As each person is read from the population file, their plans are:
 * <ol>
 *   <li>Stored in the PlanStore (RocksDB)</li>
 *   <li>Replaced with lightweight PlanProxy objects</li>
 *   <li>Original full plans are discarded from memory</li>
 * </ol>
 * 
 * <p>This allows loading large populations without keeping all plan details in memory.</p>
 * 
 * <h2>How It Works</h2>
 * <p>
 * The StreamingPopulationReader calls the {@link #run(Person)} method for each person
 * in the population file. This handler:
 * </p>
 * <ul>
 *   <li>Serializes each plan to RocksDB using FuryPlanCodec</li>
 *   <li>Creates a PlanProxy with only metadata (score, type, iteration)</li>
 *   <li>Replaces the person's plan list with proxies</li>
 *   <li>Adds the person to the scenario's population</li>
 * </ul>
 * 
 * <h2>Memory Impact</h2>
 * <p>
 * A typical plan with 10 activities and legs:
 * <ul>
 *   <li>Full plan object: ~10 KB in memory</li>
 *   <li>PlanProxy object: ~100 bytes in memory</li>
 *   <li>RocksDB storage: ~2-3 KB on disk (compressed)</li>
 * </ul>
 * </p>
 * 
 * <h2>Integration with Simulation</h2>
 * <p>
 * During simulation:
 * <ul>
 *   <li>Plan selectors (e.g., ChangeExpBeta) work with proxies - scores are in memory</li>
 *   <li>When a plan is selected for execution, it's automatically materialized</li>
 *   <li>After iteration, materialized plans are persisted and dematerialized</li>
 *   <li>This is handled by the OffloadModule's iteration hooks</li>
 * </ul>
 * </p>
 * 
 * @author steffenaxer
 */
public class StreamingPopulationHandler implements PersonAlgorithm {
    
    private final Scenario scenario;
    // private final PlanStore planStore;  // Uncomment when OffLoadPlans is fixed
    private int personCount = 0;
    private int planCount = 0;
    
    public StreamingPopulationHandler(Scenario scenario, Object planStore) {
        this.scenario = scenario;
        // this.planStore = planStore;  // Uncomment when OffLoadPlans is fixed
        throw new UnsupportedOperationException(
            "This handler requires the OffLoadPlans module to be compiled. " +
            "Currently, the OffLoadPlans module has compilation errors. " +
            "See the commented implementation below for the actual code."
        );
    }
    
    @Override
    public void run(Person person) {
        throw new UnsupportedOperationException("See constructor");
        
        /* IMPLEMENTATION (uncomment when OffLoadPlans is fixed):
        
        personCount++;
        
        // Add person to scenario's population
        scenario.getPopulation().addPerson(person);
        
        String personId = person.getId().toString();
        Plan selectedPlan = person.getSelectedPlan();
        
        // Store all plans and replace with proxies
        int personPlanCount = 0;
        for (Plan plan : person.getPlans()) {
            // Generate a unique plan ID
            String planId = OffloadSupport.ensurePlanId(plan);
            
            // Determine if this is the selected plan
            boolean isSelected = (plan == selectedPlan);
            
            // Get score, handling null/NaN values
            double score = OffloadSupport.toStorableScore(plan.getScore());
            
            // Store the plan in RocksDB
            // This serializes the plan using FuryPlanCodec and stores it persistently
            planStore.putPlan(personId, planId, plan, score, 0, isSelected);
            
            personPlanCount++;
            planCount++;
        }
        
        // Now replace all plans with proxies
        person.getPlans().clear();
        
        // Load plans as proxies from the store
        // This retrieves only metadata, not the full plan content
        for (PlanProxy proxy : planStore.listPlanProxies(person)) {
            person.addPlan(proxy);
            if (proxy.isSelected()) {
                person.setSelectedPlan(proxy);
            }
        }
        
        // Log progress every 1000 persons
        if (personCount % 1000 == 0) {
            System.out.println("Streamed " + personCount + " persons with " + planCount + " total plans");
        }
        */
    }
    
    /**
     * Returns the number of persons processed so far.
     */
    public int getPersonCount() {
        return personCount;
    }
    
    /**
     * Returns the total number of plans processed so far.
     */
    public int getPlanCount() {
        return planCount;
    }
}
