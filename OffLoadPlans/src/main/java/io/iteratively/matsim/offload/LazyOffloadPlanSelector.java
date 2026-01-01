package io.iteratively.matsim.offload;

import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.replanning.selectors.PlanSelector;
import org.matsim.utils.objectattributes.attributable.Attributes;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A plan selector that operates on offloaded plans without materializing them.
 *
 * <p>This selector wraps a delegate {@link PlanSelector} and uses lightweight
 * {@link PlanProxy} objects instead of fully materialized plans. This approach
 * minimizes memory usage during plan selection for removal.</p>
 *
 * <p>The selector loads only {@link PlanHeader} metadata from the {@link PlanStore},
 * creates proxy objects, and delegates the actual selection logic to the wrapped selector.</p>
 */
public final class LazyOffloadPlanSelector implements PlanSelector<Plan, Person> {
    private final PlanSelector<Plan, Person> delegate;
    private final PlanStore store;
    private final int currentIteration;

    /**
     * Creates a new lazy offload plan selector.
     *
     * @param delegate         the underlying selector that performs the actual selection logic
     * @param store            the plan store containing offloaded plans
     * @param currentIteration the current simulation iteration number
     */
    public LazyOffloadPlanSelector(PlanSelector<Plan, Person> delegate, PlanStore store, int currentIteration) {
        this.delegate = delegate;
        this.store = store;
        this.currentIteration = currentIteration;
    }

    /**
     * Selects a plan for removal without materializing offloaded plans.
     *
     * <p>This method performs the following steps:</p>
     * <ol>
     *   <li>Loads plan headers (metadata only) from the store</li>
     *   <li>Creates lightweight proxy objects for each plan</li>
     *   <li>Delegates selection to the wrapped selector using proxies</li>
     *   <li>Updates the active plan ID in the store if a proxy was selected</li>
     * </ol>
     *
     * @param member the person whose plans are being evaluated
     * @return the selected plan (as a proxy), or {@code null} if no plans exist
     */
    @Override
    public Plan selectPlan(HasPlansAndId<Plan, Person> member) {
        Person person = (Person) member;
        String personId = person.getId().toString();

        // Load only plan headers (without materialization)
        List<PlanHeader> headers = store.listPlanHeaders(personId);

        if (headers.isEmpty()) {
            return null;
        }

        // Create lightweight proxies for selection
        List<Plan> proxies = headers.stream()
                .map(h -> new PlanProxy(h, person, store))
                .collect(Collectors.toList());

        // Temporary person with proxies for the delegate selector
        TemporaryPlanHolder holder = new TemporaryPlanHolder(person, proxies);

        // Delegate selects based on score/type (no materialization needed)
        Plan selected = delegate.selectPlan(holder);

        if (selected instanceof PlanProxy proxy) {
            store.setActivePlanId(personId, proxy.getPlanId());
            return proxy;
        }

        return selected;
    }

    /**
     * A lightweight holder that wraps a person with proxy plans for selection.
     *
     * <p>This class allows the delegate selector to operate on proxy plans
     * without modifying the original person's plan list.</p>
     */
    private static class TemporaryPlanHolder implements HasPlansAndId<Plan, Person> {
        private final Person person;
        private final List<Plan> plans;

        /**
         * Creates a temporary plan holder.
         *
         * @param person the original person
         * @param plans  the list of proxy plans to use for selection
         */
        TemporaryPlanHolder(Person person, List<Plan> plans) {
            this.person = person;
            this.plans = plans;
        }

        @Override
        public List<? extends Plan> getPlans() {
            return plans;
        }

        @Override
        public boolean addPlan(Plan plan) {
            return plans.add(plan);
        }

        @Override
        public boolean removePlan(Plan plan) {
            return plans.remove(plan);
        }

        /**
         * Returns the currently selected plan from the proxy list.
         *
         * @return the selected plan proxy, or the first plan if none is selected
         */
        @Override
        public Plan getSelectedPlan() {
            return plans.stream().filter(p -> {
                if (p instanceof PlanProxy proxy) {
                    return proxy.getPlanId().equals(
                            person.getSelectedPlan() != null ?
                                    person.getSelectedPlan().toString() : null);
                }
                return false;
            }).findFirst().orElse(plans.isEmpty() ? null : plans.get(0));
        }

        @Override
        public void setSelectedPlan(Plan plan) {
            // Selection is handled by LazyOffloadPlanSelector
        }

        /**
         * Not supported for temporary plan holders.
         *
         * @throws UnsupportedOperationException always
         */
        @Override
        public Plan createCopyOfSelectedPlanAndMakeSelected() {
            throw new UnsupportedOperationException("Plan copying not supported for temporary holder");
        }

        @Override
        public org.matsim.api.core.v01.Id<Person> getId() {
            return person.getId();
        }

        @Override
        public Attributes getAttributes() {
            return person.getAttributes();
        }
    }
}
