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

    @Override
    public Map<String, String> getComments() {
        Map<String, String> comments = super.getComments();
        comments.put(CACHE_ENTRIES, "Maximum number of cached plans in memory");
        comments.put(STORE_DIRECTORY, "Directory for the plan store. If null, uses system temp directory");
        comments.put(STORAGE_BACKEND, "Storage backend: MAPDB or ROCKSDB (default: ROCKSDB)");
        comments.put(ENABLE_AUTODEMATERIALIZATION, "Automatically dematerialize non-selected plans to save memory (default: true)");
        comments.put(LOG_MATERIALIZATION_STATS, "Log statistics about materialized plans for debugging (default: true)");
        return comments;
    }
}
