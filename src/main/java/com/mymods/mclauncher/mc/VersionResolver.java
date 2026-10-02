package com.mymods.mclauncher.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Flattens a chain of version JSONs linked by {@code inheritsFrom} into one self-contained object.
 *
 * <p>Mod loaders work by publishing a second version JSON that points at vanilla with
 * {@code inheritsFrom} and then adds its own libraries and {@code mainClass}. The game itself
 * only understands a flat list, so something has to do the merging. Doing it here means every
 * downstream step (download, classpath, argument building) keeps working unchanged.
 *
 * <p>Merging follows the order the official launcher uses: the child overrides scalar fields, and
 * lists are combined parent-first with the child's entries winning where they collide.
 */
public final class VersionResolver {

    private static final int MAX_DEPTH = 16;

    private final Path versionsDir;

    public VersionResolver(Path versionsDir) {
        this.versionsDir = versionsDir;
    }

    /**
     * Resolves a version to a flat JSON.
     *
     * @param json the version JSON as it sits on disk, which may still declare {@code inheritsFrom}
     */
    public JsonObject flatten(JsonObject json) throws IOException {
        List<JsonObject> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        chain.add(json);
        seen.add(String.valueOf(json.get("id")));
        normalizeClientPath(json);

        // Walk up the inheritance chain so the oldest ancestor is applied first.
        JsonObject current = json;
        for (int depth = 0; current.has("inheritsFrom"); depth++) {
            if (depth >= MAX_DEPTH) {
                throw new IOException("Слишком длинная цепочка inheritsFrom для "
                        + json.get("id").getAsString());
            }
            String parentId = current.get("inheritsFrom").getAsString();
            if (!seen.add(parentId)) {
                throw new IOException("Цикл inheritsFrom: " + String.join(" -> ", seen));
            }
            JsonObject parent = load(parentId);
            chain.add(parent);
            current = parent;
        }

        JsonObject merged = new JsonObject();
        // The chain was collected child-first, so it is applied oldest-first: a more derived level
        // has to be merged last for its scalars and its own library versions to win.
        for (int i = chain.size() - 1; i >= 0; i--) {
            mergeInto(merged, chain.get(i));
        }
        return merged;
    }

    /** Loads and flattens a version by id. */
    public JsonObject resolve(String versionId) throws IOException {
        return flatten(load(versionId));
    }

    /** Reads a version JSON from the versions directory. */
    public JsonObject load(String versionId) throws IOException {
        Path file = versionsDir.resolve(versionId).resolve(versionId + ".json");
        if (!Files.isRegularFile(file)) {
            throw new IOException("Файл версии не найден: " + file);
        }
        JsonObject json =
                JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        normalizeClientPath(json);
        return json;
    }

    /**
     * Fills in {@code downloads.client.path} when Mojang left it out.
     *
     * <p>Current version JSONs publish only {@code url} and {@code sha1} for the client jar, leaving
     * the path to the launcher, which stores it as {@code <id>/<id>.jar}. That convention is only
     * correct for a version's own id: a mod loader profile inherits the block from the vanilla
     * version it extends, and resolving it against the profile id would download a second ~20 MB
     * copy of the same client. Each level is normalised with its own id while its chain is walked,
     * so the inherited path keeps pointing at the vanilla jar the profile actually runs.
     */
    private static void normalizeClientPath(JsonObject json) {
        if (!json.has("id") || !json.has("downloads")) {
            return;
        }
        JsonObject downloads = json.getAsJsonObject("downloads");
        if (!downloads.has("client")) {
            return;
        }
        JsonObject client = downloads.getAsJsonObject("client");
        if (client.has("path") || !client.has("url")) {
            return;
        }
        String id = json.get("id").getAsString();
        client.addProperty("path", id + "/" + id + ".jar");
    }

    /** True when a version JSON exists on disk, regardless of what it inherits from. */
    public boolean exists(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return false;
        }
        return Files.isRegularFile(versionsDir.resolve(versionId).resolve(versionId + ".json"));
    }

    /**
     * Applies one inheritance level onto the accumulated result.
     *
     * <p>Scalars are overwritten so a child wins. {@code libraries} are concatenated and then
     * de-duplicated by maven name, keeping the last (most derived) definition of each coordinate.
     */
    private static void mergeInto(JsonObject target, JsonObject level) {
        for (var entry : level.entrySet()) {
            String key = entry.getKey();
            switch (key) {
                case "libraries" -> target.add("libraries", mergeLibraries(
                        arrayOrEmpty(target, "libraries"), arrayOrEmpty(level, "libraries")));
                case "arguments" -> target.add("arguments", mergeArguments(
                        objectOrEmpty(target, "arguments"), level.getAsJsonObject("arguments")));
                case "inheritsFrom" -> {
                    // Already expanded; keeping it would make the result non-flat.
                }
                default -> target.add(key, entry.getValue());
            }
        }
    }

    /**
     * Concatenates library lists and drops earlier entries with the same maven name.
     *
     * <p>A loader and its base version routinely ship the same coordinates at different versions;
     * the derived one has to win or the old jar is what ends up on the classpath.
     */
    private static JsonArray mergeLibraries(JsonArray parent, JsonArray child) {
        List<JsonElement> combined = new ArrayList<>(parent.size() + child.size());
        // Gson's JsonArray is Iterable but not a java.util.Collection, so it has to be copied by hand.
        for (JsonElement element : parent) {
            combined.add(element);
        }
        for (JsonElement element : child) {
            combined.add(element);
        }

        JsonArray result = new JsonArray();
        Set<String> seen = new HashSet<>();
        // Iterate backwards so the last definition of a name is emitted first and wins the
        // de-duplication, then the output is flipped back into a stable order.
        for (int i = combined.size() - 1; i >= 0; i--) {
            JsonElement element = combined.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject library = element.getAsJsonObject();
            if (library.has("name") && !seen.add(artifactKey(library.get("name").getAsString()))) {
                continue;
            }
            result.add(library);
        }
        return reverse(result);
    }

    /**
     * Identity of a library for de-duplication: {@code group:artifact} plus the classifier.
     *
     * <p>The version has to be excluded, because the whole point is to drop an older version of the
     * same artifact. Two different artifacts that happen to share a name but differ in classifier
     * are separate files and must both be kept.
     */
    private static String artifactKey(String name) {
        String[] parts = name.split(":");
        if (parts.length <= 2) {
            return name;
        }
        return parts[0] + ":" + parts[1] + (parts.length > 3 ? ":" + parts[3] : "");
    }

    private static JsonArray reverse(JsonArray source) {
        JsonArray result = new JsonArray();
        for (int i = source.size() - 1; i >= 0; i--) {
            result.add(source.get(i));
        }
        return result;
    }

    /**
     * Concatenates the {@code jvm} and {@code game} argument lists.
     *
     * <p>The base version's arguments come first because the loader's own entries assume those
     * are already present (notably {@code -cp} and the {@code mainClass} that follows it).
     */
    private static JsonObject mergeArguments(JsonObject parent, JsonObject child) {
        JsonObject result = new JsonObject();
        for (String section : List.of("jvm", "game")) {
            JsonArray base = arrayOrEmpty(parent, section);
            JsonArray derived = arrayOrEmpty(child, section);
            if (base.isEmpty() && derived.isEmpty()) {
                continue;
            }
            JsonArray merged = new JsonArray();
            for (JsonElement element : base) {
                merged.add(element);
            }
            for (JsonElement element : derived) {
                merged.add(element);
            }
            result.add(section, merged);
        }
        return result;
    }

    private static JsonArray arrayOrEmpty(JsonObject owner, String key) {
        if (!owner.has(key) || !owner.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return owner.getAsJsonArray(key);
    }

    private static JsonObject objectOrEmpty(JsonObject owner, String key) {
        if (!owner.has(key) || !owner.get(key).isJsonObject()) {
            return new JsonObject();
        }
        return owner.getAsJsonObject(key);
    }
}
