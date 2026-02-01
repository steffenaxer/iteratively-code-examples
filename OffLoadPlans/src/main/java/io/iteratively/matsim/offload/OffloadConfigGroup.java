package io.iteratively.matsim.offload;

import org.matsim.core.config.ReflectiveConfigGroup;

import java.util.Map;

public final class OffloadConfigGroup extends ReflectiveConfigGroup {

    public static final String GROUP_NAME = "offload";
    private static final String STORE_DIRECTORY = "storeDirectory";
    private static final String ENABLE_MOBSIM_MONITORING = "enableMobsimMonitoring";
    private static final String MOBSIM_MONITORING_INTERVAL_SECONDS = "mobsimMonitoringIntervalSeconds";
    private static final String ENABLE_MOBSIM_DEMATERIALIZATION = "enableMobsimDematerialization";
    private static final String ENABLE_AFTER_REPLANNING_DEMATERIALIZATION = "enableAfterReplanningDematerialization";

    private String storeDirectory = null;
    private boolean enableMobsimMonitoring = true;
    private double mobsimMonitoringIntervalSeconds = 3600.0;
    private boolean enableMobsimDematerialization = true;
    private boolean enableAfterReplanningDematerialization = true;

    public OffloadConfigGroup() {
        super(GROUP_NAME);
    }

    @StringGetter(STORE_DIRECTORY)
    public String getStoreDirectory() {
        return storeDirectory;
    }

    @StringSetter(STORE_DIRECTORY)
    public void setStoreDirectory(String storeDirectory) {
        this.storeDirectory = storeDirectory;
    }

    @StringGetter(ENABLE_MOBSIM_MONITORING)
    public boolean getEnableMobsimMonitoring() {
        return enableMobsimMonitoring;
    }

    @StringSetter(ENABLE_MOBSIM_MONITORING)
    public void setEnableMobsimMonitoring(boolean enableMobsimMonitoring) {
        this.enableMobsimMonitoring = enableMobsimMonitoring;
    }

    @StringGetter(MOBSIM_MONITORING_INTERVAL_SECONDS)
    public double getMobsimMonitoringIntervalSeconds() {
        return mobsimMonitoringIntervalSeconds;
    }

    @StringSetter(MOBSIM_MONITORING_INTERVAL_SECONDS)
    public void setMobsimMonitoringIntervalSeconds(double mobsimMonitoringIntervalSeconds) {
        this.mobsimMonitoringIntervalSeconds = mobsimMonitoringIntervalSeconds;
    }

    @StringGetter(ENABLE_MOBSIM_DEMATERIALIZATION)
    public boolean getEnableMobsimDematerialization() {
        return enableMobsimDematerialization;
    }

    @StringSetter(ENABLE_MOBSIM_DEMATERIALIZATION)
    public void setEnableMobsimDematerialization(boolean enableMobsimDematerialization) {
        this.enableMobsimDematerialization = enableMobsimDematerialization;
    }

    @StringGetter(ENABLE_AFTER_REPLANNING_DEMATERIALIZATION)
    public boolean getEnableAfterReplanningDematerialization() {
        return enableAfterReplanningDematerialization;
    }

    @StringSetter(ENABLE_AFTER_REPLANNING_DEMATERIALIZATION)
    public void setEnableAfterReplanningDematerialization(boolean enableAfterReplanningDematerialization) {
        this.enableAfterReplanningDematerialization = enableAfterReplanningDematerialization;
    }

    @Override
    public Map<String, String> getComments() {
        Map<String, String> comments = super.getComments();
        comments.put(STORE_DIRECTORY, "Directory for the plan store. If null, uses system temp directory");
        comments.put(ENABLE_MOBSIM_MONITORING, "Enable monitoring of plan materialization during MobSim (default: true)");
        comments.put(MOBSIM_MONITORING_INTERVAL_SECONDS, "Interval for MobSim monitoring in seconds (default: 3600.0)");
        comments.put(ENABLE_MOBSIM_DEMATERIALIZATION, "Enable dematerialization of non-selected plans during MobSim (default: true)");
        comments.put(ENABLE_AFTER_REPLANNING_DEMATERIALIZATION, "Enable dematerialization of non-selected plans after replanning (default: true)");
        return comments;
    }
}
