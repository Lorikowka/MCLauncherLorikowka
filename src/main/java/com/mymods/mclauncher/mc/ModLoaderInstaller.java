package com.mymods.mclauncher.mc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mymods.mclauncher.util.Http;

/**
 * Builds a runnable version JSON for a mod loader.
 *
 * <p>Fabric is handled directly through its meta API, which returns the libraries and the launch
 * entry point as JSON, so no installer has to be executed. Forge and NeoForge instead ship a jar
 * that has to be run with a JVM; that jar is downloaded, executed against the launcher's install
 * root and its output is then marked so the profile can be found again after a restart.
 */
public final class ModLoaderInstaller {

    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";

    private final Path installRoot;
    private final Path javaExecutable;
    private final Consumer<String> log;

    public ModLoaderInstaller(Path installRoot, Path javaExecutable, Consumer<String> log) {
        this.installRoot = installRoot;
        this.javaExecutable = javaExecutable;
        this.log = log;
    }

    private Path versionsDir() {
        return installRoot.resolve("versions");
    }

    /** Whether this loader can be set up, i.e. is a mod loader at all. */
    public boolean supports(ModLoaderType loader) {
        return loader.moddable();
    }

    /**
     * Writes the profile's version JSON, inheriting from the base version.
     *
     * @return the version id that was written
     */
    public String install(ModProfile profile) throws IOException, InterruptedException {
        if (!supports(profile.loader())) {
            throw new IOException(profile.loader().displayName() + " не является мод-лоадером");
        }
        if (profile.loader() == ModLoaderType.FABRIC) {
            return installFabric(profile);
        }
        return installWithInstaller(profile);
    }

    /**
     * Forge and NeoForge: run the loader's own installer and keep its version JSON.
     *
     * <p>The installer writes the JSON itself, including the libraries it downloaded, so this only
     * has to point it at the install root and then add the profile marker to the result.
     */
    private String installWithInstaller(ModProfile profile) throws IOException,
            InterruptedException {

        ModLoaderType loader = profile.loader();
        String base = profile.baseVersion();
        String loaderVersion = profile.loaderVersion();
        if (loaderVersion == null || loaderVersion.isBlank()) {
            throw new IOException("Не выбрана версия " + loader.displayName());
        }

        String expectedId = ModLoaderCatalog.profileId(loader, base, loaderVersion);
        String url = ModLoaderCatalog.installerUrl(loader, base, loaderVersion);
        log.accept(loader.displayName() + ": ставлю " + base + " / " + loaderVersion);

        Set<String> before = existingVersionIds();
        new InstallerJarRunner(installRoot, javaExecutable, log).run(url, expectedId);

        String installedId = discoverInstalledVersion(loader, expectedId, loaderVersion, before);
        Path file = versionsDir().resolve(installedId).resolve(installedId + ".json");
        JsonObject version = JsonParser
                .parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        markProfile(version, profile, loader, loaderVersion, base);
        Files.writeString(file, version.toString(), StandardCharsets.UTF_8);

        log.accept(loader.displayName() + ": профиль версии записан: " + installedId);
        return installedId;
    }

    /** Ids of the versions already present, so a fresh install can be told apart from an old one. */
    private Set<String> existingVersionIds() throws IOException {
        try (var dirs = Files.list(versionsDir())) {
            return dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    /**
     * Finds the version the installer actually wrote.
     *
     * <p>The two installers disagree on how to spell it: Forge writes {@code 1.20.1-forge-47.4.23}
     * while NeoForge writes {@code neoforge-20.4.251}, so the id cannot be derived from the
     * coordinates alone. Reading it back off disk also catches an installer that reported success but
     * wrote nothing, which otherwise surfaces much later as a missing profile.
     */
    private String discoverInstalledVersion(ModLoaderType loader, String expectedId,
            String loaderVersion, Set<String> before) throws IOException {
        List<String> usable;
        try (var dirs = Files.list(versionsDir())) {
            usable = dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(id -> id.contains(loaderVersion))
                    .filter(id -> Files.isRegularFile(versionsDir().resolve(id).resolve(id + ".json")))
                    .sorted()
                    .toList();
        }
        if (usable.isEmpty()) {
            throw new IOException(loader.displayName()
                    + ": установщик не создал профиль версии " + expectedId);
        }
        for (String id : usable) {
            if (!before.contains(id)) {
                return id;
            }
        }
        return usable.get(usable.size() - 1);
    }

    /**
     * Adds the profile marker to a version JSON an installer produced.
     *
     * <p>The installer does not know this launcher exists, so without the marker the finished
     * profile would look like an ordinary hand-written version JSON and be invisible to the
     * profile list on the next start.
     *
     * <p>The inherited version is checked rather than trusted: the loader picks which Minecraft
     * version it builds, and if that is not the one the user asked for then installing it would
     * produce a profile that is silently wrong.
     */
    private void markProfile(JsonObject version, ModProfile profile, ModLoaderType loader,
                             String loaderVersion, String base) throws IOException {

        String inherited = version.has("inheritsFrom") ? version.get("inheritsFrom").getAsString()
                : base;
        if (!inherited.equals(base)) {
            throw new IOException(loader.displayName() + " " + loaderVersion
                    + " собирается под Minecraft " + inherited + ", а был выбран " + base);
        }

        JsonObject loaderInfo = new JsonObject();
        loaderInfo.addProperty("type", loader.id());
        loaderInfo.addProperty("version", loaderVersion);
        loaderInfo.addProperty("base", base);
        version.add("modloader", loaderInfo);
    }

    /**
     * Fabric: ask the meta API for the loader's libraries and main class, then wrap them in a
     * version JSON that inherits from the vanilla version.
     */
    private String installFabric(ModProfile profile) throws IOException, InterruptedException {
        String base = profile.baseVersion();
        String loaderVersion = profile.loaderVersion();
        if (loaderVersion == null || loaderVersion.isBlank()) {
            throw new IOException("Не выбрана версия Fabric");
        }

        log.accept("Fabric: запрашиваю метаданные " + base + " / " + loaderVersion);
        JsonObject meta = Http.getJson(FABRIC_META + "/versions/loader/" + base + "/" + loaderVersion);
        JsonObject launcherMeta = meta.has("launcherMeta")
                ? meta.getAsJsonObject("launcherMeta")
                : new JsonObject();

        JsonArray libraries = new JsonArray();
        addLoaderLibraries(libraries, launcherMeta);
        addFromMaven(libraries, meta, "intermediary", "https://maven.fabricmc.net/");
        // The loader jar itself is described by meta.loader, not by the libraries list.
        JsonObject loader = meta.getAsJsonObject("loader");
        addFromMaven(libraries, loader, "maven", "https://maven.fabricmc.net/");

        JsonObject version = new JsonObject();
        version.addProperty("id", profile.id());
        version.addProperty("inheritsFrom", base);
        version.addProperty("type", "release");
        version.addProperty("mainClass", clientMainClass(launcherMeta));
        version.addProperty("releaseTime", java.time.Instant.now().toString());
        version.add("libraries", libraries);
        // A self-describing marker, so a profile can be rebuilt from this file alone after a
        // restart instead of the launcher having to keep a second list in sync with the disk.
        JsonObject loaderInfo = new JsonObject();
        loaderInfo.addProperty("type", ModLoaderType.FABRIC.id());
        loaderInfo.addProperty("version", loaderVersion);
        loaderInfo.addProperty("base", base);
        version.add("modloader", loaderInfo);

        return writeVersion(profile.id(), version);
    }

    /**
     * The client entry point.
     *
     * <p>Fabric reports {@code mainClass} as an object with a {@code client} and a {@code server}
     * key, not as a plain string, so the client half is what belongs on the command line.
     */
    private static String clientMainClass(JsonObject launcherMeta) {
        if (!launcherMeta.has("mainClass")) {
            return "net.fabricmc.loader.impl.launch.knot.KnotClient";
        }
        JsonElement mainClass = launcherMeta.get("mainClass");
        if (mainClass.isJsonObject()) {
            JsonObject sides = mainClass.getAsJsonObject();
            return sides.has("client")
                    ? sides.get("client").getAsString()
                    : "net.fabricmc.loader.impl.launch.knot.KnotClient";
        }
        return mainClass.getAsString();
    }

    /**
     * Copies the libraries the client actually needs.
     *
     * <p>{@code launcherMeta.libraries} is grouped per side, so only {@code common} and
     * {@code client} are relevant; the server entries would only bloat the download.
     */
    private static void addLoaderLibraries(JsonArray target, JsonObject launcherMeta) {
        if (!launcherMeta.has("libraries")) {
            return;
        }
        JsonElement groups = launcherMeta.get("libraries");
        if (!groups.isJsonObject()) {
            // Older responses used a flat array.
            for (JsonElement element : groups.getAsJsonArray()) {
                addEntry(target, element, "https://maven.fabricmc.net/");
            }
            return;
        }
        JsonObject bySide = groups.getAsJsonObject();
        for (String side : new String[] {"common", "client"}) {
            if (!bySide.has(side) || !bySide.get(side).isJsonArray()) {
                continue;
            }
            for (JsonElement element : bySide.getAsJsonArray(side)) {
                addEntry(target, element, null);
            }
        }
    }

    /**
     * Turns a {@code name:artifact:version} reference into a library entry.
     *
     * <p>The meta API pairs each coordinate with a maven <em>base</em> url, so the download url is
     * that base plus the usual maven layout. Hashes are carried over so the existing downloader's
     * verification still applies.
     */
    private static void addEntry(JsonArray target, JsonElement element, String defaultBase) {
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject entry = element.getAsJsonObject();
        if (!entry.has("name")) {
            return;
        }
        String base = entry.has("url") ? entry.get("url").getAsString() : defaultBase;
        if (base == null || base.isBlank()) {
            base = defaultBase;
        }
        if (base == null) {
            return;
        }
        addMaven(target, entry.get("name").getAsString(), base, entry);
    }

    /**
     * Adds a top-level entry such as {@code intermediary} or {@code loader}.
     *
     * <p>Both are objects carrying a {@code maven} coordinate, and may also carry the hash of the
     * file that coordinate points at, so the value is unwrapped rather than read as a plain string.
     */
    private static void addFromMaven(JsonArray target, JsonObject source, String key, String base) {
        if (source == null || !source.has(key)) {
            return;
        }
        JsonElement value = source.get(key);
        if (value.isJsonObject()) {
            JsonObject nested = value.getAsJsonObject();
            if (nested.has("maven")) {
                addMaven(target, nested.get("maven").getAsString(), base, nested);
            }
            return;
        }
        if (value.isJsonPrimitive()) {
            addMaven(target, value.getAsString(), base, null);
        }
    }

    /** Builds a library entry with a derived maven path and an explicit download url. */
    private static void addMaven(JsonArray target, String coordinates, String baseUrl,
                                 JsonObject hashes) {

        String[] parts = coordinates.split(":");
        if (parts.length < 3) {
            return;
        }
        String group = parts[0];
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? parts[3] : null;

        String stem = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact
                + "-" + version + (classifier == null ? "" : "-" + classifier);

        JsonObject artifactJson = new JsonObject();
        artifactJson.addProperty("path", stem + ".jar");
        artifactJson.addProperty("url", baseUrl + stem + ".jar");
        if (hashes != null && hashes.has("size")) {
            artifactJson.addProperty("size", hashes.get("size").getAsLong());
        }
        if (hashes != null && hashes.has("sha1")) {
            artifactJson.addProperty("sha1", hashes.get("sha1").getAsString());
        }

        JsonObject downloads = new JsonObject();
        downloads.add("artifact", artifactJson);

        JsonObject library = new JsonObject();
        library.addProperty("name", coordinates);
        library.add("downloads", downloads);
        target.add(library);
    }

    /**
     * Saves the version JSON where the downloader and launcher expect it.
     *
     * <p>No placeholder jar is created: this profile is not a vanilla version, and the loader
     * classes come from the library list rather than from a version jar.
     */
    private String writeVersion(String versionId, JsonObject version) throws IOException {
        Path dir = versionsDir().resolve(versionId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(versionId + ".json"), version.toString(),
                StandardCharsets.UTF_8);
        log.accept("Fabric: профиль версии записан: " + versionId);
        return versionId;
    }
}
