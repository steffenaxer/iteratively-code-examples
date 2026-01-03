package io.iteratively.matsim.offload;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.controler.listener.StartupListener;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Active watchdog that monitors and dematerializes non-selected plans that exceed their lifetime.
 * Runs continuously from simulation startup to shutdown, checking object lifetimes periodically.
 */
public final class PlanMaterializationWatchdog implements StartupListener, ShutdownListener {
    private static final Logger log = LogManager.getLogger(PlanMaterializationWatchdog.class);
    
    private final Scenario scenario;
    private Timer watchdogTimer;
    private Population currentPopulation;
    private volatile boolean isRunning = false;
    
    @Inject
    public PlanMaterializationWatchdog(Scenario scenario) {
        this.scenario = scenario;
    }
    
    private OffloadConfigGroup getConfig() {
        return ConfigUtils.addOrGetModule(scenario.getConfig(), OffloadConfigGroup.class);
    }
    
    @Override
    public void notifyStartup(StartupEvent event) {
        if (!getConfig().isEnableAutodematerialization()) {
            return;
        }
        
        currentPopulation = event.getServices().getScenario().getPopulation();
        startWatchdog();
    }
    
    @Override
    public void notifyShutdown(ShutdownEvent event) {
        stopWatchdog();
    }
    
    private void startWatchdog() {
        if (isRunning) {
            return;
        }
        
        isRunning = true;
        watchdogTimer = new Timer("PlanMaterializationWatchdog", true);
        
        long checkInterval = getConfig().getWatchdogCheckIntervalMs();
        
        watchdogTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkAndDematerialize();
            }
        }, checkInterval, checkInterval);
        
        log.info("Plan materialization watchdog started (check interval: {}ms, max plan lifetime: {}ms)", 
                checkInterval, getConfig().getMaxNonSelectedMaterializationTimeMs());
    }
    
    private void stopWatchdog() {
        if (!isRunning) {
            return;
        }
        
        isRunning = false;
        if (watchdogTimer != null) {
            watchdogTimer.cancel();
            watchdogTimer = null;
        }
        
        log.info("Plan materialization watchdog stopped");
    }
    
    private void checkAndDematerialize() {
        if (!isRunning || currentPopulation == null) {
            return;
        }
        
        try {
            long maxLifetimeMs = getConfig().getMaxNonSelectedMaterializationTimeMs();
            int dematerialized = PlanMaterializationMonitor.dematerializeAllOldNonSelected(
                    currentPopulation, maxLifetimeMs);
            
            if (dematerialized > 0) {
                if (getConfig().isLogMaterializationStats()) {
                    log.info("Watchdog: Dematerialized {} non-selected plans older than {}ms", 
                            dematerialized, maxLifetimeMs);
                    
                    // Log detailed statistics when cleanup happens
                    PlanMaterializationMonitor.logStats(currentPopulation, "watchdog cleanup");
                }
            }
        } catch (Exception e) {
            log.error("Error in plan materialization watchdog", e);
        }
    }
}
