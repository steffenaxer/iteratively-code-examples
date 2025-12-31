package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;

public class PlanStoreShutdownListener implements ShutdownListener {
    
    private final PlanStore store;
    
    @Inject
    public PlanStoreShutdownListener(PlanStore store) {
        this.store = store;
    }
    
    @Override
    public void notifyShutdown(ShutdownEvent event) {
        store.close();
    }
}
