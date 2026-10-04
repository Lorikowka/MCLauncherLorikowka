package com.mymods.mclauncher.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModProfileStoreTest {

    @Test
    void profileIsRebuiltFromItsOwnJson(@TempDir Path versionsDir) throws IOException {
        writeFabric(versionsDir, "fabric-1.20.1-0.19.5", "1.20.1", "0.19.5");

        ModProfile profile = new ModProfileStore(versionsDir).find("fabric-1.20.1-0.19.5");

        assertEquals("fabric-1.20.1-0.19.5", profile.id());
        assertEquals("1.20.1", profile.baseVersion());
        assertEquals(ModLoaderType.FABRIC, profile.loader());
        assertEquals("0.19.5", profile.loaderVersion());
    }

    @Test
    void namedAliasKeepsItsNameAndLeavesTheSourceProfileUntouched(@TempDir Path versionsDir)
            throws IOException {
        String sourceId = "fabric-1.20.1-0.19.5";
        writeFabric(versionsDir, sourceId, "1.20.1", "0.19.5");
        ModProfileStore store = new ModProfileStore(versionsDir);

        ModProfile alias = store.createNamedAlias(sourceId, "My Adventure Pack");

        // The folder is named after the build alone, so the user can find it without decoding
        // an id like <base>-<loader>-<build>-pack-<slug>-<hash>.
        assertEquals("my-adventure-pack", alias.id());
        assertEquals("My Adventure Pack", alias.displayName());
        assertEquals(sourceId, store.find(sourceId).id());
        assertEquals("My Adventure Pack", store.find(alias.id()).displayName());
        assertTrue(Files.isRegularFile(versionsDir.resolve(alias.id()).resolve(alias.id() + ".json")));

        // A second build with the same name must not overwrite the first one.
        ModProfile twin = store.createNamedAlias(sourceId, "My Adventure Pack");
        assertEquals("my-adventure-pack-2", twin.id());
        assertEquals("My Adventure Pack", store.find(alias.id()).displayName());
    }

    @Test
    void vanillaVersionIsNotAProfile(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "1.20.1", "{\"id\":\"1.20.1\",\"mainClass\":\"Vanilla\"}");

        ModProfileStore store = new ModProfileStore(versionsDir);
        assertNull(store.find("1.20.1"));
        assertFalse(store.isProfile("1.20.1"));
        assertEquals(0, store.all().size());
    }

    @Test
    void modsInFolderAreListed(@TempDir Path versionsDir) throws IOException {
        writeFabric(versionsDir, "fabric-1.20.1-0.19.5", "1.20.1", "0.19.5");
        Path mods = versionsDir.resolve("fabric-1.20.1-0.19.5").resolve("mods");
        Files.createDirectories(mods);
        Files.writeString(mods.resolve("ferritecore.jar"), "x");
        Files.writeString(mods.resolve("readme.txt"), "x");
        Files.createDirectories(mods.resolve("nested"));

        assertEquals(java.util.List.of("ferritecore.jar"),
                new ModProfileStore(versionsDir).modsIn("fabric-1.20.1-0.19.5"));
    }

    @Test
    void missingOrBrokenFilesYieldNoProfile(@TempDir Path versionsDir) throws IOException {
        Files.createDirectories(versionsDir.resolve("empty"));
        write(versionsDir, "broken", "{ not json");
        writeFabric(versionsDir, "good", "1.20.1", "0.19.5");

        ModProfileStore store = new ModProfileStore(versionsDir);
        // One bad file must not take the whole list down with it.
        assertEquals(java.util.List.of("good"), store.ids());
        assertNull(store.find("broken"));
        assertNull(store.find("empty"));
        assertNull(store.find(null));
        assertNull(store.find("  "));
    }

    @Test
    void vanillaIsInstalledAndProfilesCountToo(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "1.20.1", "{\"id\":\"1.20.1\",\"mainClass\":\"Vanilla\"}");
        Files.writeString(versionsDir.resolve("1.20.1").resolve("1.20.1.jar"), "x");
        writeFabric(versionsDir, "fabric-1.20.1-0.19.5", "1.20.1", "0.19.5");
        // A vanilla version whose download never finished: JSON only, no jar.
        write(versionsDir, "1.21.1", "{\"id\":\"1.21.1\",\"mainClass\":\"Vanilla\"}");

        VersionStore store = new VersionStore(versionsDir, versionsDir.resolve("assets"));

        assertTrue(store.isInstalled("1.20.1"));
        // A profile is installed by its version JSON, since it owns no client jar.
        assertTrue(store.isInstalled("fabric-1.20.1-0.19.5"));
        assertFalse(store.isInstalled("1.21.1"));
        assertFalse(store.isInstalled("nope"));
        assertFalse(store.isInstalled(null));

        assertTrue(store.installed().contains("1.20.1"));
        assertTrue(store.installed().contains("fabric-1.20.1-0.19.5"));
        assertFalse(store.installed().contains("1.21.1"));
    }

    @Test
    void profileIsDeletableLikeAVanillaVersion(@TempDir Path versionsDir) throws IOException {
        writeFabric(versionsDir, "fabric-1.20.1-0.19.5", "1.20.1", "0.19.5");
        Path mods = versionsDir.resolve("fabric-1.20.1-0.19.5").resolve("mods");
        Files.createDirectories(mods);
        Files.writeString(mods.resolve("a.jar"), "x");

        VersionStore store = new VersionStore(versionsDir, versionsDir.resolve("assets"));
        assertTrue(store.delete("fabric-1.20.1-0.19.5"));
        assertFalse(Files.exists(versionsDir.resolve("fabric-1.20.1-0.19.5")));
    }

    @Test
    void deleteAllIncludesProfiles(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "1.20.1", "{\"id\":\"1.20.1\"}");
        Files.writeString(versionsDir.resolve("1.20.1").resolve("1.20.1.jar"), "x");
        writeFabric(versionsDir, "fabric-1.20.1-0.19.5", "1.20.1", "0.19.5");

        VersionStore store = new VersionStore(versionsDir, versionsDir.resolve("assets"));
        assertEquals(2, store.deleteAll());
    }

    @Test
    void deleteAllLeavesAnythingElseAlone(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "1.20.1", "{\"id\":\"1.20.1\"}");
        Files.writeString(versionsDir.resolve("1.20.1").resolve("1.20.1.jar"), "x");
        // A half-finished download: a directory, but neither a jar nor a profile JSON.
        Files.createDirectories(versionsDir.resolve("1.21.1"));
        Path assets = versionsDir.resolve("assets");
        Files.createDirectories(assets.resolve("objects"));
        Files.writeString(assets.resolve("objects").resolve("ab"), "x");

        VersionStore store = new VersionStore(versionsDir, assets);
        assertEquals(1, store.deleteAll());

        assertFalse(Files.exists(versionsDir.resolve("1.20.1")));
        // Deleting everything installed must not clean up a version that is not installed yet,
        // nor the shared assets, which every other version still needs.
        assertTrue(Files.isDirectory(versionsDir.resolve("1.21.1")));
        assertTrue(Files.isRegularFile(assets.resolve("objects").resolve("ab")));
    }

    @Test
    void markerlessForgeProfileRecoversItsVersionFromTheLaunchArguments(@TempDir Path versionsDir)
            throws IOException {
        // An installer writes its JSON before the launcher adds the marker; such a file still
        // names the loader in the --fml arguments, which is what repair needs to run an installer.
        write(versionsDir, "1.19.2-forge-43.5.2", """
                {"id":"1.19.2-forge-43.5.2","inheritsFrom":"1.19.2",
                 "mainClass":"cpw.mods.modlauncher.Launcher",
                 "libraries":[{"name":"net.minecraftforge:fmlloader:43.5.2"}],
                 "arguments":{"game":["--launchTarget","forgeclient",
                 "--fml.forgeVersion","43.5.2","--fml.mcVersion","1.19.2",
                 "--fml.forgeGroup","net.minecraftforge"]}}
                """);

        ModProfile profile = new ModProfileStore(versionsDir).find("1.19.2-forge-43.5.2");

        assertNotNull(profile);
        assertEquals(ModLoaderType.FORGE, profile.loader());
        assertEquals("1.19.2", profile.baseVersion());
        assertEquals("43.5.2", profile.loaderVersion());
    }

    @Test
    void loaderVersionFallsBackToTheIdWhenTheArgumentsAreGone(@TempDir Path versionsDir)
            throws IOException {
        write(versionsDir, "1.19.2-forge-43.5.2", """
                {"id":"1.19.2-forge-43.5.2","inheritsFrom":"1.19.2",
                 "libraries":[{"name":"net.minecraftforge:fmlloader:43.5.2"}]}
                """);

        ModProfile profile = new ModProfileStore(versionsDir).find("1.19.2-forge-43.5.2");

        assertNotNull(profile);
        assertEquals("43.5.2", profile.loaderVersion());
    }

    @Test
    void markerlessFabricProfileRecoversItsVersionFromTheId(@TempDir Path versionsDir)
            throws IOException {
        write(versionsDir, "fabric-1.20.1-0.19.5", """
                {"id":"fabric-1.20.1-0.19.5","inheritsFrom":"1.20.1","mainClass":"KnotClient",
                 "libraries":[{"name":"net.fabricmc:fabric-loader:0.19.5"}]}
                """);

        ModProfile profile = new ModProfileStore(versionsDir).find("fabric-1.20.1-0.19.5");

        assertNotNull(profile);
        assertEquals(ModLoaderType.FABRIC, profile.loader());
        assertEquals("0.19.5", profile.loaderVersion());
    }

    private static void writeFabric(Path versionsDir, String id, String base, String loaderVersion)
            throws IOException {
        write(versionsDir, id, """
                {"id":"%s","inheritsFrom":"%s","mainClass":"KnotClient",
                 "modloader":{"type":"fabric","version":"%s","base":"%s"}}
                """.formatted(id, base, loaderVersion, base));
    }

    private static void write(Path versionsDir, String id, String json) throws IOException {
        Path dir = versionsDir.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), json, StandardCharsets.UTF_8);
    }
}
