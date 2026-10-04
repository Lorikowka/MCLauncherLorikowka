package com.mymods.mclauncher.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.mymods.mclauncher.util.Http;

/** Fetches and caches version manifests and version metadata from Mojang. */
public final class VersionCatalog {

    public static final String MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    /** A copy younger than this is used as-is, so a normal relaunch needs no network at all. */
    private static final long MAX_CACHE_AGE_MS = 60 * 60 * 1000;

    private final Map<String, McVersion> byId = new ConcurrentHashMap<>();
    private List<McVersion> all = List.of();
    private final Path cacheFile;

    /** No disk cache: every {@link #refresh()} goes to Mojang. */
    public VersionCatalog() {
        this(null);
    }

    /**
     * Keeps a copy of the manifest in {@code cacheFile}: a fresh one replaces the network fetch,
     * and an offline start falls back to whatever the last successful run left behind.
     */
    public VersionCatalog(Path cacheFile) {
        this.cacheFile = cacheFile;
    }

    /** Downloads the manifest and caches it, falling back to the disk copy when offline. */
    public void refresh() throws IOException, InterruptedException {
        if (cacheFile != null && freshCache() && loadCache()) {
            return;
        }
        try {
            JsonObject manifest = Http.getJson(MANIFEST_URL);
            apply(manifest);
            saveCache(manifest);
        } catch (IOException networkFailed) {
            if (cacheFile != null && loadCache()) {
                return;
            }
            throw networkFailed;
        }
    }

    private boolean freshCache() {
        if (cacheFile == null) {
            return false;
        }
        try {
            long age = System.currentTimeMillis()
                    - Files.getLastModifiedTime(cacheFile).toMillis();
            return age < MAX_CACHE_AGE_MS;
        } catch (IOException e) {
            return false;
        }
    }

    /** Replaces the catalog with the disk copy; false when it is missing or unreadable. */
    private boolean loadCache() {
        try {
            apply(com.google.gson.JsonParser.parseString(Files.readString(cacheFile)).getAsJsonObject());
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("Cached version manifest unreadable: " + cacheFile);
            return false;
        }
    }

    /** Best effort: a full disk must not turn a working refresh into a failure. */
    private void saveCache(JsonObject manifest) {
        if (cacheFile == null) {
            return;
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, manifest.toString());
        } catch (IOException e) {
            System.err.println("Cannot write version manifest cache: " + e.getMessage());
        }
    }

    private void apply(JsonObject manifest) {
        all = McVersion.fromJson(manifest);
        byId.clear();
        for (McVersion version : all) {
            byId.put(version.id(), version);
        }
    }

    public List<McVersion> all() {
        return all;
    }

    /**
     * Registers versions without touching the network, so a UI check can run against a known
     * catalog instead of downloading the whole manifest.
     */
    public void addVersions(List<McVersion> versions) {
        List<McVersion> merged = new ArrayList<>(all);
        merged.addAll(versions);
        all = List.copyOf(merged);
        for (McVersion version : versions) {
            byId.put(version.id(), version);
        }
    }

    /** Stable releases first by newest, matching how vanilla's own UI orders things. */
    public List<McVersion> releases() {
        return all.stream().filter(McVersion::isRelease).toList();
    }

    public McVersion find(String id) {
        return byId.get(id);
    }

    /** Picks a sane default: newest release the launcher is able to run. */
    public McVersion defaultVersion() {
        for (String preferred : new String[] {"1.21.4", "1.21.1", "1.20.4", "1.20.1", "1.19.2", "1.16.5"}) {
            McVersion match = byId.get(preferred);
            if (match != null && match.isRelease()) {
                return match;
            }
        }
        return releases().isEmpty() ? null : releases().get(0);
    }

    /**
     * Downloads the version JSON into {@code versions/<id>/<id>.json} and returns it.
     * Cached on disk so a relaunch does not need the network.
     */
    public JsonObject loadVersionJson(McVersion version, Path versionsDir)
            throws IOException, InterruptedException {

        Path dir = versionsDir.resolve(version.id());
        Path cached = dir.resolve(version.id() + ".json");
        if (Files.isRegularFile(cached)) {
            try {
                return com.google.gson.JsonParser.parseString(Files.readString(cached)).getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                System.err.println("Cached version json unreadable, refetching: " + version.id());
            }
        }

        JsonObject json = Http.getJson(version.url());
        Files.createDirectories(dir);
        Files.writeString(cached, json.toString());
        return json;
    }
}