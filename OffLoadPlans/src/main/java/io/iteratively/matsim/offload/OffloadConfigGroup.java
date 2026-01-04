package io.iteratively.matsim.offload;

import org.matsim.core.config.ReflectiveConfigGroup;

import java.util.Map;

public final class OffloadConfigGroup extends ReflectiveConfigGroup {

    public static final String GROUP_NAME = "offload";

    private static final String CACHE_ENTRIES = "cacheEntries";
    private static final String STORE_DIRECTORY = "storeDirectory";
    private static final String STORAGE_BACKEND = "storageBackend";
    private static final String ENABLE_AUTODEMATERIALIZATION = "enableAutodematerialization";
    private static final String LOG_MATERIALIZATION_STATS = "logMaterializationStats";
    private static final String MAX_NON_SELECTED_MATERIALIZATION_TIME_MS = "maxNonSelectedMaterializationTimeMs";
    private static final String WATCHDOG_CHECK_INTERVAL_MS = "watchdogCheckIntervalMs";
    private static final String ENABLE_MOBSIM_MONITORING = "enableMobsimMonitoring";
    private static final String MOBSIM_MONITORING_INTERVAL_SECONDS = "mobsimMonitoringIntervalSeconds";

    public static final String MAPDB_FILE_NAME = "plans.mapdb";
    public static final String ROCKSDB_DIR_NAME = "plans_rocksdb";

    public enum StorageBackend {
        MAPDB,
        ROCKSDB
    }

    private int cacheEntries = 1000;
    private String storeDirectory = null;
    private StorageBackend storageBackend = StorageBackend.ROCKSDB;
    private boolean enableAutodematerialization = true;
    private boolean logMaterializationStats = true;
    private long maxNonSelectedMaterializationTimeMs = 1000; // 1 second default - keep very short to avoid excessive memory usage
    private long watchdogCheckIntervalMs = 2000; // 2 seconds default
    private boolean enableMobsimMonitoring = true;
    private double mobsimMonitoringIntervalSeconds = 3600.0; // 1 hour default

    public OffloadConfigGroup() {
        super(GROUP_NAME);
    }

    @StringGetter(CACHE_ENTRIES)
    public String getCacheEntriesAsString() {
        return String.valueOf(cacheEntries);
    }

    @StringSetter(CACHE_ENTRIES)
    public void setCacheEntriesFromString(String cacheEntries) {
        this.cacheEntries = Integer.parseInt(cacheEntries);
    }

    public int getCacheEntries() {
        return cacheEntries;
    }

    public void setCacheEntries(int cacheEntries) {
        this.cacheEntries = cacheEntries;
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

    @StringGetter(ENABLE_AUTODEMATERIALIZATION)
    public String getEnableAutodematerializationAsString() {
        return String.valueOf(enableAutodematerialization);
    }

    @StringSetter(ENABLE_AUTODEMATERIALIZATION)
    public void setEnableAutodematerializationFromString(String enable) {
        this.enableAutodematerialization = Boolean.parseBoolean(enable);
    }

    public boolean isEnableAutodematerialization() {
        return enableAutodematerialization;
    }

    public void setEnableAutodematerialization(boolean enableAutodematerialization) {
        this.enableAutodematerialization = enableAutodematerialization;
    }

    @StringGetter(LOG_MATERIALIZATION_STATS)
    public String getLogMaterializationStatsAsString() {
        return String.valueOf(logMaterializationStats);
    }

    @StringSetter(LOG_MATERIALIZATION_STATS)
    public void setLogMaterializationStatsFromString(String log) {
        this.logMaterializationStats = Boolean.parseBoolean(log);
    }

    public boolean isLogMaterializationStats() {
        return logMaterializationStats;
    }

    public void setLogMaterializationStats(boolean logMaterializationStats) {
        this.logMaterializationStats = logMaterializationStats;
    }

    @StringGetter(MAX_NON_SELECTED_MATERIALIZATION_TIME_MS)
    public String getMaxNonSelectedMaterializationTimeMsAsString() {
        return String.valueOf(maxNonSelectedMaterializationTimeMs);
    }

    @StringSetter(MAX_NON_SELECTED_MATERIALIZATION_TIME_MS)
    public void setMaxNonSelectedMaterializationTimeMsFromString(String timeMs) {
        this.maxNonSelectedMaterializationTimeMs = Long.parseLong(timeMs);
    }

    public long getMaxNonSelectedMaterializationTimeMs() {
        return maxNonSelectedMaterializationTimeMs;
    }

    public void setMaxNonSelectedMaterializationTimeMs(long maxNonSelectedMaterializationTimeMs) {
        this.maxNonSelectedMaterializationTimeMs = maxNonSelectedMaterializationTimeMs;
    }

    @StringGetter(WATCHDOG_CHECK_INTERVAL_MS)
    public String getWatchdogCheckIntervalMsAsString() {
        return String.valueOf(watchdogCheckIntervalMs);
    }

    @StringSetter(WATCHDOG_CHECK_INTERVAL_MS)
    public void setWatchdogCheckIntervalMsFromString(String intervalMs) {
        this.watchdogCheckIntervalMs = Long.parseLong(intervalMs);
    }

    public long getWatchdogCheckIntervalMs() {
        return watchdogCheckIntervalMs;
    }

    public void setWatchdogCheckIntervalMs(long watchdogCheckIntervalMs) {
        this.watchdogCheckIntervalMs = watchdogCheckIntervalMs;
    }

    @StringGetter(ENABLE_MOBSIM_MONITORING)
    public String getEnableMobsimMonitoringAsString() {
        return String.valueOf(enableMobsimMonitoring);
    }

    @StringSetter(ENABLE_MOBSIM_MONITORING)
    public void setEnableMobsimMonitoringFromString(String enable) {
        this.enableMobsimMonitoring = Boolean.parseBoolean(enable);
    }

    public boolean isEnableMobsimMonitoring() {
        return enableMobsimMonitoring;
    }

    public void setEnableMobsimMonitoring(boolean enableMobsimMonitoring) {
        this.enableMobsimMonitoring = enableMobsimMonitoring;
    }

    @StringGetter(MOBSIM_MONITORING_INTERVAL_SECONDS)
    public String getMobsimMonitoringIntervalSecondsAsString() {
        return String.valueOf(mobsimMonitoringIntervalSeconds);
    }

    @StringSetter(MOBSIM_MONITORING_INTERVAL_SECONDS)
    public void setMobsimMonitoringIntervalSecondsFromString(String intervalSeconds) {
        this.mobsimMonitoringIntervalSeconds = Double.parseDouble(intervalSeconds);
    }

    public double getMobsimMonitoringIntervalSeconds() {
        return mobsimMonitoringIntervalSeconds;
    }

    public void setMobsimMonitoringIntervalSeconds(double mobsimMonitoringIntervalSeconds) {
        this.mobsimMonitoringIntervalSeconds = mobsimMonitoringIntervalSeconds;
    }

    @Override
    public Map<String, String> getComments() {
        Map<String, String> comments = super.getComments();
        comments.put(CACHE_ENTRIES, "Maximum number of cached plans in memory");
        comments.put(STORE_DIRECTORY, "Directory for the plan store. If null, uses system temp directory");
        comments.put(STORAGE_BACKEND, "Storage backend: MAPDB or ROCKSDB (default: ROCKSDB)");
        comments.put(ENABLE_AUTODEMATERIALIZATION, "Automatically dematerialize non-selected plans to save memory (default: true)");
        comments.put(LOG_MATERIALIZATION_STATS, "Log statistics about materialized plans for debugging (default: true)");
        comments.put(MAX_NON_SELECTED_MATERIALIZATION_TIME_MS, "Maximum time in milliseconds a non-selected plan can remain materialized (default: 1000ms = 1 second). Keep short to avoid excessive memory usage.");
        comments.put(WATCHDOG_CHECK_INTERVAL_MS, "Interval in milliseconds for the watchdog to check for old materialized plans (default: 2000ms = 2 seconds)");
        comments.put(ENABLE_MOBSIM_MONITORING, "Enable monitoring of plan materialization during MobSim (default: true)");
        comments.put(MOBSIM_MONITORING_INTERVAL_SECONDS, "Interval in simulation seconds for monitoring plan materialization during MobSim (default: 3600.0 = 1 hour)");
        return comments;
    }
}
