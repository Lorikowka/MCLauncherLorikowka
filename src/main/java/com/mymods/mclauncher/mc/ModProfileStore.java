package com.mymods.mclauncher.mc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Discovers mod loader profiles on disk.
 *
 * <p>Profiles are not kept in a separate index: each one writes its own details into its version
 * JSON, so the disk is the single source of truth. A profile the user deleted simply stops being
 * found, and there is no list that can disagree with the filesystem.
 */
public final class ModProfileStore {

    private final Path versionsDir;

    public ModProfileStore(Path versionsDir) {
        this.versionsDir = versionsDir;
    }

    /** Every profile found, newest first by last-modified time. */
    public List<ModProfile> all() {
        List<Path> dirs = new ArrayList<>();
        if (Files.isDirectory(versionsDir)) {
            try (Stream<Path> children = Files.list(versionsDir)) {
                children.filter(Files::isDirectory).forEach(dirs::add);
            } catch (IOException e) {
                return List.of();
            }
        }
        dirs.sort(Comparator.comparingLong(this::lastModified).reversed());

        List<ModProfile> profiles = new ArrayList<>();
        for (Path dir : dirs) {
            ModProfile profile = read(dir.getFileName().toString());
            if (profile != null) {
                profiles.add(profile);
            }
        }
        return profiles;
    }

    public List<String> ids() {
        return all().stream().map(ModProfile::id).toList();
    }

    /** The profile for an id, or null when the id is not a mod loader profile. */
    public ModProfile find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return read(id);
    }

    public boolean isProfile(String id) {
        return find(id) != null;
    }

    /**
     * Reads one profile from its version JSON.
     *
     * <p>Returns null for anything that is not a profile, including a version JSON that is missing
     * or unreadable, so a corrupt file hides one profile instead of breaking the whole list.
     */
    private ModProfile read(String id) {
        Path file = versionsDir.resolve(id).resolve(id + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();

            // The loader library in the JSON is written by the installer, so it is the part of the
            // profile we did not author and can trust most. The marker is our own bookkeeping and is
            // read second, for profiles we wrote before this was derived and for the rare JSON whose
            // libraries do not name a loader.
            ModLoaderType type = ModLoaderCatalog.detectFromLibraries(json);
            JsonObject info = json.has("modloader") && json.get("modloader").isJsonObject()
                    ? json.getAsJsonObject("modloader")
                    : null;
            if (type == null && info != null) {
                type = ModLoaderType.parse(info.get("type").getAsString());
            }
            if (type == null || !type.moddable()) {
                return null;
            }
            String base = info != null && info.has("base") ? info.get("base").getAsString()
                    : baseFrom(json);
            String loaderVersion = info != null && info.has("version")
                    && info.get("version").isJsonPrimitive()
                    ? info.get("version").getAsString()
                    : loaderVersionFrom(json, type);
            String displayName = info != null && info.has("name")
                    ? info.get("name").getAsString()
                    : null;
            return new ModProfile(id, base, type, loaderVersion, modsIn(id), displayName);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public ModProfile createNamedAlias(String sourceId, String displayName) throws IOException {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isBlank()) {
            throw new IOException("Введи название профиля");
        }
        ModProfile source = find(sourceId);
        if (source == null) {
            throw new IOException("Не найден профиль загрузчика: " + sourceId);
        }

        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.isBlank()) {
            slug = "pack";
        }
        if (slug.length() > 24) {
            slug = slug.substring(0, 24).replaceAll("-$", "");
        }
        // The folder is just the build name: a version id like <base>-<loader>-<build>-pack-<slug>-<hash>
        // tells a user nothing about where to drop mods, while "storymain" matches what they typed.
        // Slug never contains dots, so it cannot collide with a vanilla or loader profile id; the
        // loop only guards against another build already claiming the name.
        String id = slug;
        for (int n = 2; Files.exists(versionsDir.resolve(id)); n++) {
            id = slug + "-" + n;
        }
        Path sourceFile = versionsDir.resolve(sourceId).resolve(sourceId + ".json");
        JsonObject json = JsonParser.parseString(Files.readString(sourceFile, StandardCharsets.UTF_8))
                .getAsJsonObject().deepCopy();
        json.addProperty("id", id);
        JsonObject marker = json.has("modloader") && json.get("modloader").isJsonObject()
                ? json.getAsJsonObject("modloader")
                : new JsonObject();
        marker.addProperty("type", source.loader().id());
        marker.addProperty("version", source.loaderVersion());
        marker.addProperty("base", source.baseVersion());
        marker.addProperty("name", name);
        json.add("modloader", marker);

        Path targetDir = versionsDir.resolve(id);
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(id + ".json");
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return find(id);
    }

    /** Falls back to the inherited id, which is the base version for every installed profile. */
    private static String baseFrom(JsonObject json) {
        return json.has("inheritsFrom") ? json.get("inheritsFrom").getAsString() : "";
    }

    /**
     * Loader version of a profile whose marker does not carry one.
     *
     * <p>Profiles written before the marker stored its version — or read from an installer's
     * output before the marker was added — still name the loader in the launch arguments the
     * installer left behind, and in the id the launcher and the installers agree on. Without one
     * of those the profile loads but cannot be repaired or reinstalled, because both need the
     * version to build the installer URL.
     */
    private static String loaderVersionFrom(JsonObject json, ModLoaderType type) {
        String fromArguments = LaunchPreflight.fmlArgument(json, "--fml.forgeVersion");
        if (fromArguments != null && !fromArguments.isBlank()) {
            return fromArguments;
        }
        String base = baseFrom(json);
        String id = json.has("id") ? json.get("id").getAsString() : "";
        String prefix = type == ModLoaderType.FABRIC
                ? "fabric-" + base + "-"
                : base + "-" + type.id() + "-";
        if (base.isEmpty() || !id.startsWith(prefix)) {
            return null;
        }
        // What follows the prefix is the loader version, then possibly a pack alias suffix. A
        // version offered for installation never contains a dash of its own (see the build
        // patterns in ModLoaderCatalog), so the first segment is the whole version.
        String rest = id.substring(prefix.length());
        int alias = rest.indexOf('-');
        return alias < 0 ? rest : rest.substring(0, alias);
    }

    /** Mod file names currently in the profile's mods folder. */
    public List<String> modsIn(String id) {
        Path mods = versionsDir.resolve(id).resolve("mods");
        if (!Files.isDirectory(mods)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(mods)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private long lastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
