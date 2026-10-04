package com.mymods.mclauncher.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mymods.mclauncher.auth.OfflineSession;
import com.mymods.mclauncher.config.LauncherConfig;
import com.mymods.mclauncher.GameStarter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for "I picked Forge and got vanilla".
 *
 * <p>The fixtures are the two version JSONs a real Forge install produces on disk: Mojang's
 * {@code 1.20.1} and the {@code 1.20.1-forge-47.4.23} the Forge installer wrote over it. They are
 * used verbatim, because the bug this guards against lives entirely in how those two files are
 * combined: the child inherits {@code net.minecraft.client.main.Main} and its libraries from the
 * parent, so anything that loses the child turns the launch into a vanilla one without failing.
 *
 * <p>Every assertion here is about what the launcher would actually execute, not about what a
 * lookup returned.
 */
class ForgeProfileLaunchTest {

    private static final String FORGE_ID = "1.20.1-forge-47.4.23";
    private static final String VANILLA_ID = "1.20.1";

    /** FML's bootstrap; the one class that proves Forge is what is running. */
    private static final String FML_MAIN_CLASS = "cpw.mods.bootstraplauncher.BootstrapLauncher";

    /** The entry point of an unmodded game, i.e. the thing a broken launch falls back to. */
    private static final String VANILLA_MAIN_CLASS = "net.minecraft.client.main.Main";

    @TempDir
    Path installRoot;

    @BeforeEach
    void copyFixtures() throws IOException {
        // The store and the resolver both address versions as <id>/<id>.json, so the fixtures are
        // laid out in the install the same way an installer would have left them.
        copyFixture(VANILLA_ID);
        copyFixture(FORGE_ID);
        // The client jar is what marks a vanilla version as installed. Its content is irrelevant:
        // nothing here reads it.
        Path jar = installRoot.resolve("versions").resolve(VANILLA_ID).resolve(VANILLA_ID + ".jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "not a real jar");
    }

    private void copyFixture(String id) throws IOException {
        String resource = "/fixtures/forge/" + id + "/" + id + ".json";
        try (InputStream in = ForgeProfileLaunchTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            Path target = installRoot.resolve("versions").resolve(id);
            Files.createDirectories(target);
            Files.copy(in, target.resolve(id + ".json"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private VersionMetadata flat(String id) throws IOException {
        JsonObject json = new VersionResolver(installRoot.resolve("versions")).resolve(id);
        return VersionMetadata.fromJson(json);
    }

    private CommandBuilder.Context context(VersionMetadata version) {
        return new CommandBuilder.Context(
                installRoot.resolve("game"),
                installRoot.resolve("assets"),
                version.assetsIndexId,
                installRoot.resolve("versions").resolve(version.id),
                installRoot.resolve("libraries"),
                installRoot.resolve("natives").resolve(version.id),
                new OfflineSession("Tester"),
                version.id,
                version.type,
                version.mainClass,
                1280,
                720,
                "",
                List.of("-Xmx2G"),
                version.libraries,
                GameDownloader.clientJar(version, installRoot.resolve("versions")));
    }

    private List<String> launch(VersionMetadata version) {
        return CommandBuilder.build(Path.of("java"), version, context(version), false, null);
    }

    // --- the profile has to be recognisable as a profile -------------------------------

    @Test
    void forgeProfileIsFoundOnDiskAndTypedAsForge() {
        ModProfileStore store = new ModProfileStore(installRoot.resolve("versions"));

        ModProfile profile = store.find(FORGE_ID);

        assertNotNull(profile, "the installed Forge profile must be found by its id");
        assertEquals(ModLoaderType.FORGE, profile.loader());
        assertEquals(VANILLA_ID, profile.baseVersion());
        assertTrue(profile.moddable());
    }

    @Test
    void vanillaVersionIsNotMistakenForAProfile() {
        ModProfileStore store = new ModProfileStore(installRoot.resolve("versions"));

        assertEquals(null, store.find(VANILLA_ID),
                "1.20.1 carries no loader library, so it must stay a plain version");
    }

    // --- flattening must keep the child, not the parent --------------------------------

    @Test
    void flatteningAForgeProfileKeepsTheFmlEntryPoint() throws IOException {
        // The parent supplies net.minecraft.client.main.Main and the child replaces it with FML's
        // bootstrap. Merging in the wrong order, or treating libraries as whole-value, silently
        // produces a command line that runs an unmodded game.
        assertEquals(FML_MAIN_CLASS, flat(FORGE_ID).mainClass);
    }

    @Test
    void flatteningAForgeProfileInheritsTheVanillaClientJar() throws IOException {
        // Forge has no downloads block of its own; the client's entry arrives from 1.20.1 and must
        // keep pointing at the vanilla jar rather than a second copy under the profile's name.
        VersionMetadata forge = flat(FORGE_ID);

        assertEquals(VANILLA_ID + "/" + VANILLA_ID + ".jar", forge.clientJarPath);
        assertEquals(installRoot.resolve("versions").resolve("1.20.1").resolve("1.20.1.jar"),
                GameDownloader.clientJar(forge, installRoot.resolve("versions")));
    }

    @Test
    void flatteningAForgeProfileKeepsBothLoadersLibraries() throws IOException {
        VersionMetadata forge = flat(FORGE_ID);

        assertTrue(forge.libraries.stream().anyMatch(l -> l.name().startsWith("net.minecraftforge:")),
                "the loader's own libraries must survive the merge");
        assertTrue(forge.libraries.stream().anyMatch(l -> l.name().startsWith("cpw.mods:")),
                "modlauncher must survive the merge");
    }

    // --- the command that would run ------------------------------------------------------

    @Test
    void forgeLaunchCommandIsNotAVanillaLaunch() throws IOException {
        String joined = String.join(" ", launch(flat(FORGE_ID)));

        // The single most important assertion: if the main class is the vanilla one, the game
        // starts, looks normal, and no loader is present. Nothing else reports an error.
        assertTrue(joined.contains(FML_MAIN_CLASS),
                "Forge must be entered through FML, not the vanilla main class: " + joined);
        assertFalse(joined.contains(VANILLA_MAIN_CLASS),
                "the vanilla entry point must not be on the Forge command line: " + joined);
        assertTrue(joined.contains("--version " + FORGE_ID),
                "the game must be told which version it is running: " + joined);
    }

    @Test
    void forgeLaunchUsesTheLegacyClassPathAndKeepsTheModulePath() throws IOException {
        List<String> command = launch(flat(FORGE_ID));
        String joined = String.join(" ", command);

        // FML builds MC-BOOTSTRAP out of -DlegacyClassPath. With a -cp instead, the layer comes up
        // empty and the game dies with "Module layer not found: MC-BOOTSTRAP".
        assertFalse(joined.contains("-cp "), "FML must not use -cp: " + joined);
        assertFalse(joined.contains("-classpath"), "FML must not use -classpath: " + joined);

        String layer = command.stream()
                .filter(a -> a.startsWith("-DlegacyClassPath="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no -DlegacyClassPath in: " + joined))
                .substring("-DlegacyClassPath=".length());

        assertTrue(layer.contains("modlauncher"), "modlauncher missing from the layer: " + layer);
        // The obfuscated jar has no usable module name, so it belongs on neither path.
        assertFalse(layer.contains("1.20.1.jar"), "the client jar must stay off the layer: " + layer);
        assertTrue(joined.contains("-p "), "the module path the loader declared must survive: " + joined);
    }

    @Test
    void vanillaLaunchIsStillAVanillaLaunch() throws IOException {
        // The control for the tests above: the same pipeline over the parent JSON has to keep
        // working, otherwise fixing Forge could have broken everything else.
        List<String> command = launch(flat(VANILLA_ID));
        String joined = String.join(" ", command);

        assertTrue(joined.contains(VANILLA_MAIN_CLASS), joined);
        assertTrue(joined.contains("-cp "), "vanilla still needs a classpath: " + joined);
        assertFalse(joined.contains("-DlegacyClassPath="), "vanilla has no FML layer: " + joined);
        assertFalse(joined.contains(FORGE_ID), "vanilla must not mention a loader profile: " + joined);
    }

    // --- the mods folder the loader will actually read ------------------------------------

    @Test
    void modsFolderIsWhereTheLoaderLooksForMods() throws IOException {
        // Fabric is pointed at a per-profile folder with -Dfabric.modsFolder, but Forge and
        // NeoForge read <gameDir>/mods and have no equivalent switch. Reporting a folder the
        // loader never opens means mods the user installed are silently ignored.
        LauncherConfig config = new LauncherConfig();
        config.gameDir = installRoot.toString();
        GameStarter starter = new GameStarter(config, new VersionCatalog());
        ModProfile forgeProfile = new ModProfile(FORGE_ID, VANILLA_ID, ModLoaderType.FORGE,
                "47.4.23", List.of(), null);
        ModProfile fabricProfile = new ModProfile("fabric-1.20.1-0.19.5", VANILLA_ID,
                ModLoaderType.FABRIC, "0.19.5", List.of(), null);

        Path fabricMods = starter.loaderModsDir("fabric-1.20.1-0.19.5", fabricProfile);
        Path forgeMods = starter.loaderModsDir(FORGE_ID, forgeProfile);

        assertEquals(config.versionsDir().resolve("fabric-1.20.1-0.19.5").resolve("mods"),
                fabricMods,
                "Fabric keeps a per-profile mods folder, pointed at with -Dfabric.modsFolder");
        // Forge reads mods from its game directory, isolated per loader profile.
        Path gameDir = installRoot.resolve("instances").resolve(FORGE_ID);
        assertEquals(gameDir.resolve("mods"), forgeMods,
                "Forge has no mods-folder switch and always reads <gameDir>/mods, so any other "
                        + "folder means the user's mods are silently ignored");
    }

    @Test
    void forgeAndNeoForgeShareTheGameModsFolder() {
        LauncherConfig config = new LauncherConfig();
        config.gameDir = installRoot.toString();
        config.isolateGameDir = false;
        GameStarter starter = new GameStarter(config, new VersionCatalog());
        ModProfile forgeProfile = new ModProfile(FORGE_ID, VANILLA_ID, ModLoaderType.FORGE,
                "47.4.23", List.of(), null);
        ModProfile neoProfile = new ModProfile("neoforge-1.20.1-20.4.100", VANILLA_ID,
                ModLoaderType.NEOFORGE, "20.4.100", List.of(), null);

        assertEquals(Path.of(config.gameDir).resolve("mods"),
                starter.loaderModsDir(FORGE_ID, forgeProfile));
        assertEquals(Path.of(config.gameDir).resolve("mods"),
                starter.loaderModsDir(neoProfile.id(), neoProfile),
                "NeoForge reads the game folder just like Forge does");
    }

    /** Reads a fixture as raw text, for tests that assert on the file itself. */
    private String readFixture(String id) {
        try (InputStream in = ForgeProfileLaunchTest.class
                .getResourceAsStream("/fixtures/forge/" + id + "/" + id + ".json")) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + id);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Kept so an unreadable or malformed fixture fails loudly instead of silently skipping. */
    @Test
    void fixturesParseAsVersionJson() {
        for (String id : List.of(VANILLA_ID, FORGE_ID)) {
            JsonObject json = JsonParser.parseString(readFixture(id)).getAsJsonObject();
            assertEquals(id, json.get("id").getAsString(), "fixture id");
        }
    }
}