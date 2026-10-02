package com.mymods.mclauncher.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonParser;
import com.mymods.mclauncher.auth.AuthSession;
import com.mymods.mclauncher.auth.OfflineSession;
import org.junit.jupiter.api.Test;

class CommandBuilderTest {

    private static CommandBuilder.Context context(VersionMetadata version, String username) {
        return new CommandBuilder.Context(
                Path.of("game"),
                Path.of("assets"),
                version.assetsIndexId,
                Path.of("versions").resolve(version.id),
                Path.of("libraries"),
                Path.of("natives").resolve(version.id),
                new OfflineSession(username),
                version.id,
                version.type,
                version.mainClass,
                1280,
                720,
                "",
                List.of("-Xmx2G"),
                version.libraries,
                null);
    }

    @Test
    void comparesDottedVersionsNumerically() {
        assertTrue(CommandBuilder.compareAtLeast("1.20.1", "1.20"));
        assertTrue(CommandBuilder.compareAtLeast("1.20", "1.20"));
        assertTrue(CommandBuilder.compareAtLeast("1.21", "1.20.4"));
        assertFalse(CommandBuilder.compareAtLeast("1.19.4", "1.20"));
        assertFalse(CommandBuilder.compareAtLeast("1.9", "1.10"));
    }

    @Test
    void readsJavaMajorFromVersionJson() {
        VersionMetadata version = VersionMetadata.fromJson(JsonParser.parseString("""
                {
                  "id": "1.20.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "javaVersion": {"component": "java-runtime-gamma", "majorVersion": "17"},
                  "libraries": []
                }
                """).getAsJsonObject());

        assertTrue(version.requiredJavaMajor() == 17);
    }

    @Test
    void modernArgumentsGetFullySubstituted() {
        VersionMetadata version = VersionMetadata.fromJson(JsonParser.parseString("""
                {
                  "id": "1.20.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "assetIndex": {"id": "5"},
                  "libraries": [],
                  "arguments": {
                    "game": [
                      "--username", "${auth_player_name}",
                      "--version", "${version_name}",
                      "--gameDir", "${game_directory}",
                      "--assetsDir", "${assets_root}",
                      "--assetIndex", "${assets_index_name}",
                      "--uuid", "${auth_uuid}",
                      "--accessToken", "${auth_access_token}",
                      "--clientId", "${auth_xuid}",
                      "--userType", "${user_type}",
                      "--versionType", "${version_type}",
                      "--quickPlayPath", "${quickPlayPath}"
                    ]
                  }
                }
                """).getAsJsonObject());

        List<String> command = CommandBuilder.build(
                Path.of("java"),
                version,
                context(version, "Tester"),
                false,
                null);

        String joined = String.join(" ", command);
        assertFalse(joined.contains("${"), "unsubstituted placeholder in: " + joined);
        assertTrue(joined.contains("--assetIndex 5"), "asset index should be the index id: " + joined);
        assertTrue(joined.contains("--userType legacy"));
        // The main class must survive substitution, so the command really is runnable.
        assertTrue(joined.contains("net.minecraft.client.main.Main"));
    }

    @Test
    void featureGatedArgumentsAreNotPassed() {
        // Real snippet from the 1.20.1 version JSON. Passing this puts the game into demo mode.
        VersionMetadata version = VersionMetadata.fromJson(JsonParser.parseString("""
                {
                  "id": "1.20.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "libraries": [],
                  "arguments": {
                    "game": [
                      "--username", "${auth_player_name}",
                      {"rules": [{"action": "allow",
                                  "features": {"is_demo_user": true}}],
                       "value": "--demo"}
                    ]
                  }
                }
                """).getAsJsonObject());

        String joined = String.join(" ", CommandBuilder.build(
                Path.of("java"), version, context(version, "Tester"), false, null));

        assertFalse(joined.contains("--demo"), "demo mode must stay off: " + joined);
        assertTrue(joined.contains("--username Tester"), joined);
    }

    @Test
    void legacyVersionGetsHistoricalArgumentSet() {
        VersionMetadata version = VersionMetadata.fromJson(JsonParser.parseString("""
                {
                  "id": "1.12.2",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "assetIndex": {"id": "1.12"},
                  "libraries": []
                }
                """).getAsJsonObject());

        List<String> command = CommandBuilder.build(
                Path.of("java"), version, context(version, "Tester"), false, null);
        String joined = String.join(" ", command);

        assertFalse(joined.contains("${"), "unsubstituted placeholder in: " + joined);
        assertTrue(joined.contains("-Djava.library.path="), joined);
        assertTrue(joined.contains("--assetIndex 1.12"), joined);
        assertTrue(joined.contains("--width 1280"), joined);
    }

    @Test
    void offlineSessionProducesStableUuid() {
        AuthSession session = new OfflineSession("Tester");
        assertEquals(session.uuid(), new OfflineSession("Tester").uuid());
        assertEquals(32, session.uuid().length());
        assertEquals("legacy", session.userType());
        assertEquals("Tester", session.username());
    }

    @Test
    void missingJavaVersionMeansNoRequirement() {
        VersionMetadata version = VersionMetadata.fromJson(JsonParser.parseString("""
                {
                  "id": "1.12.2",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "libraries": []
                }
                """).getAsJsonObject());

        assertTrue(version.requiredJavaMajor() == 0);
    }

    @Test
    void fmlLaunchesFromLegacyClassPathInsteadOfCp() {
        // Shape of a Forge/NeoForge version JSON: FML modules on the module path and an
        // ignoreList naming the jars the loader injects into its own layer.
        VersionMetadata version = VersionMetadata.fromJson(JsonParser.parseString("""
                {
                  "id": "1.20.1-forge-47.4.23",
                  "type": "release",
                  "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
                  "assetIndex": {"id": "5"},
                  "libraries": [
                    {"name": "net.minecraftforge:modlauncher:10.0.9"},
                    {"name": "org.ow2.asm:asm:9.5"}
                  ],
                  "arguments": {
                    "jvm": [
                      "-p", "${library_directory}/net/minecraftforge/fmlcore/1.20.1/fmlcore.jar",
                      "-DignoreList=fmlcore,javafmllanguage,lowcodelanguage,mclanguage,forge-"
                    ],
                    "game": ["--username", "${auth_player_name}"]
                  }
                }
                """).getAsJsonObject());

        List<String> command = CommandBuilder.build(
                Path.of("java"),
                version,
                context(version, "Tester"),
                false,
                null);

        String joined = String.join(" ", command);

        // FML reads -DlegacyClassPath to build its MC-BOOTSTRAP layer, so -cp would leave the
        // layer empty and the game dies with "Module layer not found: MC-BOOTSTRAP".
        assertFalse(joined.contains("-cp "), "FML must not use -cp: " + joined);
        assertFalse(joined.contains("-classpath"), "FML must not use -classpath: " + joined);

        String legacy = command.stream()
                .filter(a -> a.startsWith("-DlegacyClassPath="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no -DlegacyClassPath in: " + joined));
        String layer = legacy.substring("-DlegacyClassPath=".length());

        // The plain Minecraft jar is not a valid module name ("1.20.1") and must stay out of
        // both the module path and the legacy layer.
        assertFalse(layer.contains("versions" + java.io.File.separator + version.id),
                "version jar must not be on the legacy layer: " + layer);
        assertFalse(layer.contains("1.20.1.jar"), "version jar must not be on the legacy layer: " + layer);

        // Real dependencies do have to be there, otherwise the layer cannot load anything.
        assertTrue(layer.contains("modlauncher"), "modlauncher missing from layer: " + layer);
        assertTrue(layer.contains("asm"), "libraries missing from layer: " + layer);

        // The module path stays as the version declared it.
        assertTrue(joined.contains("-p "), "module path should survive: " + joined);
        assertTrue(joined.contains("BootstrapLauncher"), "main class missing: " + joined);
    }
}