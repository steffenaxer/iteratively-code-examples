package io.iteratively.matsim.offload;

import org.matsim.core.config.ReflectiveConfigGroup;

import java.util.Map;

public final class OffloadConfigGroup extends ReflectiveConfigGroup {

    public static final String GROUP_NAME = "offload";
    private static final String STORE_DIRECTORY = "storeDirectory";
    private static final String STORAGE_BACKEND = "storageBackend";
    private static final String ENABLE_MOBSIM_MONITORING = "enableMobsimMonitoring";
    private static final String MOBSIM_MONITORING_INTERVAL_SECONDS = "mobsimMonitoringIntervalSeconds";
    private static final String ENABLE_MOBSIM_DEMATERIALIZATION = "enableMobsimDematerialization";

    public static final String DB_FILE_NAME = "plans.mapdb";

    public enum StorageBackend {
        MAPDB,
        ROCKSDB
    }

    private String storeDirectory = null;
    private StorageBackend storageBackend = StorageBackend.MAPDB;
    private boolean enableMobsimMonitoring = true;
    private double mobsimMonitoringIntervalSeconds = 3600.0;
    private boolean enableMobsimDematerialization = true;

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

    @StringGetter(STORAGE_BACKEND)
    public String getStorageBackendAsString() {
        return storageBackend.name();
    }

    @StringSetter(STORAGE_BACKEND)
    public void setStorageBackendFromString(String backend) {
        this.storageBackend = StorageBackend.valueOf(backend.toUpperCase());
    }

    public StorageBackend getStorageBackend() {
        return storageBackend;
    }

    public void setStorageBackend(StorageBackend storageBackend) {
        this.storageBackend = storageBackend;
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

    @Override
    public Map<String, String> getComments() {
        Map<String, String> comments = super.getComments();
        comments.put(STORE_DIRECTORY, "Directory for the plan store. If null, uses system temp directory");
        comments.put(STORAGE_BACKEND, "Storage backend: MAPDB or ROCKSDB (default: MAPDB)");
        comments.put(ENABLE_MOBSIM_MONITORING, "Enable monitoring of plan materialization during MobSim (default: true)");
        comments.put(MOBSIM_MONITORING_INTERVAL_SECONDS, "Interval for MobSim monitoring in seconds (default: 3600.0)");
        comments.put(ENABLE_MOBSIM_DEMATERIALIZATION, "Enable dematerialization of non-selected plans during MobSim (default: true)");
        return comments;
    }
}
