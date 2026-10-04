package com.mymods.mclauncher.mc;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Everything the launcher needs to know about one Minecraft version. */
public final class VersionMetadata {

    public final String id;
    public final String type;
    public final String mainClass;
    public final String releaseTime;
    public final String assetsIndexId;
    public final String assetsRoot;
    public final String complianceLevel;
    public final List<Library> libraries;
    public final JsonObject arguments;
    public final String clientJarPath;
    public final String clientSha1;
    public final String clientSize;
    public final JsonObject javaVersion;

    private VersionMetadata(String id, String type, String mainClass, String releaseTime,
                            String assetsIndexId, String assetsRoot, String complianceLevel,
                            List<Library> libraries, JsonObject arguments, String clientJarPath,
                            String clientSha1, String clientSize, JsonObject javaVersion) {
        this.id = id;
        this.type = type;
        this.mainClass = mainClass;
        this.releaseTime = releaseTime;
        this.assetsIndexId = assetsIndexId;
        this.assetsRoot = assetsRoot;
        this.complianceLevel = complianceLevel;
        this.libraries = libraries;
        this.arguments = arguments;
        this.clientJarPath = clientJarPath;
        this.clientSha1 = clientSha1;
        this.clientSize = clientSize;
        this.javaVersion = javaVersion;
    }

    public static VersionMetadata fromJson(JsonObject json) {
        String id = json.get("id").getAsString();
        String type = json.has("type") ? json.get("type").getAsString() : "release";

        List<Library> libraries = new ArrayList<>();
        if (json.has("libraries")) {
            for (var element : json.getAsJsonArray("libraries")) {
                libraries.add(Library.fromJson(element.getAsJsonObject()));
            }
        }

        String assetsIndexId = "legacy";
        String assetsRoot = "legacy";
        if (json.has("assetIndex")) {
            JsonObject assetIndex = json.getAsJsonObject("assetIndex");
            if (assetIndex.has("id")) {
                assetsIndexId = assetIndex.get("id").getAsString();
            }
        }
        if (json.has("assets")) {
            assetsRoot = json.get("assets").getAsString();
        }

        String clientJarPath = null;
        String clientSha1 = null;
        String clientSize = null;
        if (json.has("downloads") && json.getAsJsonObject("downloads").has("client")) {
            JsonObject client = json.getAsJsonObject("downloads").getAsJsonObject("client");
            if (client.has("path")) {
                clientJarPath = client.get("path").getAsString();
            }
            if (client.has("sha1")) {
                clientSha1 = client.get("sha1").getAsString();
            }
            if (client.has("size")) {
                clientSize = client.get("size").getAsString();
            }
        }

        return new VersionMetadata(
                id,
                type,
                json.get("mainClass").getAsString(),
                json.has("releaseTime") ? json.get("releaseTime").getAsString() : "",
                assetsIndexId,
                assetsRoot,
                json.has("complianceLevel") ? json.get("complianceLevel").getAsString() : null,
                libraries,
                json.has("arguments") ? json.getAsJsonObject("arguments") : null,
                clientJarPath,
                clientSha1,
                clientSize,
                json.has("javaVersion") ? json.getAsJsonObject("javaVersion") : null);
    }

    /** Major version the game wants, e.g. 17 for 1.19.2, or 0 when unspecified. */
    public int requiredJavaMajor() {
        if (javaVersion == null || !javaVersion.has("majorVersion")) {
            return 0;
        }
        try {
            return Integer.parseInt(javaVersion.get("majorVersion").getAsString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Whether Mojang's game arguments can accept the direct-connect address. */
    public boolean supportsQuickPlayMultiplayer() {
        if (arguments == null || !arguments.has("game")) {
            return false;
        }
        return containsQuickPlayPlaceholder(arguments.getAsJsonArray("game"));
    }

    private static boolean containsQuickPlayPlaceholder(JsonElement element) {
        if (element.isJsonPrimitive()) {
            return element.getAsJsonPrimitive().isString()
                    && element.getAsString().contains("${quickPlayMultiplayer}");
        }
        if (element.isJsonArray()) {
            JsonArray values = element.getAsJsonArray();
            for (JsonElement value : values) {
                if (containsQuickPlayPlaceholder(value)) {
                    return true;
                }
            }
        } else if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (containsQuickPlayPlaceholder(entry.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Libraries whose main jar has to exist locally: the classpath itself plus the pre-1.13
     * entries that pair that same jar with a {@code natives} classifier map. Pure 1.13+ natives
     * entries are excluded — their artifact is the natives jar, handled by {@link #nativeLibraries()}.
     */
    public List<Library> classpathLibraries() {
        return libraries.stream()
                .filter(Library::appliesHere)
                .filter(l -> !l.isNativeArtifact() || l.hasNativesMap())
                .toList();
    }

    /**
     * Native jars to unpack before launch.
     *
     * <p>For 1.13+ these are plain library entries whose own path is the native jar. For older
     * versions the native jar sits next to the main one under a {@code -natives} name and is
     * downloaded from {@code downloads.classifiers}.
     */
    public List<Library> nativeLibraries() {
        return libraries.stream()
                .filter(Library::appliesHere)
                .filter(l -> l.isNativeArtifact()
                        && (l.hasNativesMap() ? l.nativesUrl() != null : l.artifactUrl() != null))
                .toList();
    }
}