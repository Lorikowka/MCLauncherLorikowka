package com.mymods.mclauncher.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.mymods.mclauncher.util.Http;

/** Fetches and caches version manifests and version metadata from Mojang. */
public final class VersionCatalog {

    public static final String MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    private final Map<String, McVersion> byId = new ConcurrentHashMap<>();
    private List<McVersion> all = List.of();

    /** Downloads the manifest and caches it. */
    public void refresh() throws IOException, InterruptedException {
        JsonObject manifest = Http.getJson(MANIFEST_URL);
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

    public List<McVersion> snapshots() {
        return all.stream().filter(v -> !v.isRelease()).toList();
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

    /** Latest release id from the manifest, used by the launcher for updates. */
    public String latestReleaseId() {
        McVersion first = releases().stream()
                .filter(v -> !v.id().toLowerCase(Locale.ROOT).contains("pre"))
                .findFirst()
                .orElse(null);
        return first == null ? null : first.id();
    }
}