package io.iteratively.matsim.offload;

import org.matsim.core.config.ReflectiveConfigGroup;

import java.util.Map;

public final class OffloadConfigGroup extends ReflectiveConfigGroup {

    public static final String GROUP_NAME = "offload";

    private static final String CACHE_ENTRIES = "cacheEntries";
    private static final String STORE_DIRECTORY = "storeDirectory";
    private static final String STORAGE_BACKEND = "storageBackend";

    public static final String MAPDB_FILE_NAME = "plans.mapdb";
    public static final String ROCKSDB_DIR_NAME = "plans_rocksdb";

    public enum StorageBackend {
        MAPDB,
        ROCKSDB
    }

    private int cacheEntries = 1000;
    private String storeDirectory = null;
    private StorageBackend storageBackend = StorageBackend.ROCKSDB;

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

    @Override
    public Map<String, String> getComments() {
        Map<String, String> comments = super.getComments();
        comments.put(CACHE_ENTRIES, "Maximum number of cached plans in memory");
        comments.put(STORE_DIRECTORY, "Directory for the plan store. If null, uses system temp directory");
        comments.put(STORAGE_BACKEND, "Storage backend: MAPDB or ROCKSDB (default: ROCKSDB)");
        return comments;
    }
}
