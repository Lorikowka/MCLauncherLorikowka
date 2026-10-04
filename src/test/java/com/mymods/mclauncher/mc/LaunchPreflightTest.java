package com.mymods.mclauncher.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchPreflightTest {

    private static final String FORGE_GROUP = "net.minecraftforge";
    private static final String MCP = "20220805.130853";

    @Test
    void forgeProfileWithNoArtifactsReportsAllThreeJars(@TempDir Path libraries) {
        List<Path> missing = LaunchPreflight.missingLoaderArtifacts(forgeJson(FORGE_GROUP),
                libraries);

        assertEquals(3, missing.size());
        String joined = missing.toString();
        assertTrue(joined.contains("client-1.19.2-" + MCP + "-srg.jar"), joined);
        assertTrue(joined.contains("client-1.19.2-" + MCP + "-extra.jar"), joined);
        assertTrue(joined.contains("forge-1.19.2-43.5.2-client.jar"), joined);
        assertTrue(joined.startsWith("[" + libraries), "missing files must live under libraries: "
                + joined);
    }

    @Test
    void forgeArtifactsPresentReportNothing(@TempDir Path libraries) throws IOException {
        writeForgeArtifacts(libraries, "client");

        assertEquals(List.of(),
                LaunchPreflight.missingLoaderArtifacts(forgeJson(FORGE_GROUP), libraries));
    }

    @Test
    void universalForgeJarAlsoCountsAsInstalled(@TempDir Path libraries) throws IOException {
        writeForgeArtifacts(libraries, "universal");

        assertEquals(List.of(),
                LaunchPreflight.missingLoaderArtifacts(forgeJson(FORGE_GROUP), libraries));
    }

    @Test
    void loadersWithoutAVerifiedLayoutAreNotChecked(@TempDir Path libraries) {
        // NeoForge reuses FML's argument names but writes a different layout, which nothing here
        // has verified against a real install: blocking a launch on a guess would be worse than
        // letting FML report it itself.
        assertEquals(List.of(),
                LaunchPreflight.missingLoaderArtifacts(forgeJson("net.neoforged"), libraries));
        // Fabric declares no --fml arguments at all.
        assertEquals(List.of(),
                LaunchPreflight.missingLoaderArtifacts(new JsonObject(), libraries));
    }

    @Test
    void classpathFilesInTheCommandAreChecked(@TempDir Path root) throws IOException {
        Path present = root.resolve("present.jar");
        Files.writeString(present, "x");
        Path absent = root.resolve("absent.jar");
        List<String> command = List.of("java", "-cp",
                present + File.pathSeparator + absent, "Main");

        assertEquals(List.of(absent), LaunchPreflight.missing(command, root));

        Files.writeString(absent, "x");
        assertEquals(List.of(), LaunchPreflight.missing(command, root));
    }

    @Test
    void legacyClassPathModulePathAndAgentAreChecked(@TempDir Path root) throws IOException {
        Path legacyPresent = root.resolve("legacy-present.jar");
        Path legacyAbsent = root.resolve("legacy-absent.jar");
        Path moduleAbsent = root.resolve("module-absent.jar");
        Path agent = root.resolve("injector.jar");
        Files.writeString(legacyPresent, "x");
        List<String> command = List.of("java",
                "-DlegacyClassPath=" + legacyPresent + File.pathSeparator + legacyAbsent,
                "-javaagent:" + agent + "=https://session.example/",
                "-p", moduleAbsent.toString(),
                "Main");

        List<Path> missing = LaunchPreflight.missing(command, root);

        assertEquals(3, missing.size(), String.valueOf(missing));
        assertTrue(missing.contains(legacyAbsent), String.valueOf(missing));
        assertTrue(missing.contains(moduleAbsent), String.valueOf(missing));
        assertTrue(missing.contains(agent), String.valueOf(missing));

        for (Path file : List.of(legacyAbsent, moduleAbsent, agent)) {
            Files.writeString(file, "x");
        }
        assertEquals(List.of(), LaunchPreflight.missing(command, root));
    }

    @Test
    void aForgeCommandLineCarriesTheLoaderCheck(@TempDir Path libraries) {
        List<String> command = List.of("java", "-Xmx2G", "cpw.mods.modlauncher.Launcher",
                "--launchTarget", "forgeclient",
                "--fml.forgeVersion", "43.5.2",
                "--fml.mcVersion", "1.19.2",
                "--fml.forgeGroup", FORGE_GROUP,
                "--fml.mcpVersion", MCP);

        List<Path> missing = LaunchPreflight.missing(command, libraries);

        assertEquals(3, missing.size(), String.valueOf(missing));
    }

    @Test
    void aVanillaCommandWithItsFilesPresentIsClean(@TempDir Path root) throws IOException {
        Path jar = root.resolve("client.jar");
        Files.writeString(jar, "x");
        List<String> command = List.of("java", "-cp", jar.toString(), "net.minecraft.client.main.Main",
                "--username", "Steve");

        assertEquals(List.of(), LaunchPreflight.missing(command, root));
    }

    @Test
    void describeNamesEveryMissingFileAndWhatToDo() {
        String text = LaunchPreflight.describe(
                List.of(Path.of("libraries", "a.jar"), Path.of("libraries", "b.jar")));

        assertTrue(text.contains("a.jar"), text);
        assertTrue(text.contains("b.jar"), text);
        assertTrue(text.contains("Переустанови"), text);
    }

    /**
     * The game arguments as the Forge installer writes them, mixing all three token shapes the
     * JSON format allows: bare strings, an object with a value array, and an object with a plain
     * value.
     */
    private static JsonObject forgeJson(String forgeGroup) {
        JsonObject arguments = new JsonObject();
        JsonArray game = new JsonArray();
        game.add("--launchTarget");
        game.add("forgeclient");
        game.add("--fml.forgeVersion");
        game.add("43.5.2");
        game.add("--fml.mcVersion");
        game.add("1.19.2");

        JsonArray groupValue = new JsonArray();
        groupValue.add("--fml.forgeGroup");
        groupValue.add(forgeGroup);
        JsonObject groupArgument = new JsonObject();
        groupArgument.add("value", groupValue);
        game.add(groupArgument);

        JsonObject mcpArgument = new JsonObject();
        mcpArgument.addProperty("value", "--fml.mcpVersion");
        game.add(mcpArgument);
        game.add(MCP);

        arguments.add("game", game);
        JsonObject json = new JsonObject();
        json.add("arguments", arguments);
        return json;
    }

    /** The three jars FML resolves, in the layout a real install has them. */
    private static void writeForgeArtifacts(Path libraries, String forgeClassifier)
            throws IOException {
        String mcMcp = "1.19.2-" + MCP;
        Path clientDir = libraries.resolve("net").resolve("minecraft").resolve("client")
                .resolve(mcMcp);
        Files.createDirectories(clientDir);
        Files.writeString(clientDir.resolve("client-" + mcMcp + "-srg.jar"), "x");
        Files.writeString(clientDir.resolve("client-" + mcMcp + "-extra.jar"), "x");

        Path forgeDir = libraries.resolve("net").resolve("minecraftforge").resolve("forge")
                .resolve("1.19.2-43.5.2");
        Files.createDirectories(forgeDir);
        Files.writeString(forgeDir.resolve("forge-1.19.2-43.5.2-" + forgeClassifier + ".jar"), "x");
    }
}
