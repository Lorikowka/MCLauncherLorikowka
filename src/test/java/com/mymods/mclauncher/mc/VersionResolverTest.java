package com.mymods.mclauncher.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the inheritance flattening that mod loader profiles depend on.
 *
 * <p>These use hand-written version JSON rather than anything downloaded, so a change in a
 * third-party API cannot make the merge rules quietly wrong.
 */
class VersionResolverTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void inheritsFromIsExpanded(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "1.20.1", """
                {"id":"1.20.1","mainClass":"net.minecraft.client.main.Main",
                 "assetIndex":{"id":"12"},
                 "libraries":[{"name":"org.lwjgl:lwjgl:3.3.1"}]}
                """);
        write(versionsDir, "fabric-1.20.1", """
                {"id":"fabric-1.20.1","inheritsFrom":"1.20.1",
                 "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
                 "libraries":[{"name":"net.fabricmc:fabric-loader:0.15.11"}]}
                """);

        JsonObject flat = new VersionResolver(versionsDir).resolve("fabric-1.20.1");

        // The loader's main class wins, the base version's assets are inherited.
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", flat.get("mainClass").getAsString());
        assertEquals("12", flat.getAsJsonObject("assetIndex").get("id").getAsString());
        // Both libraries survive, base first.
        JsonArray libraries = flat.getAsJsonArray("libraries");
        assertEquals(2, libraries.size());
        assertEquals("org.lwjgl:lwjgl:3.3.1",
                libraries.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("net.fabricmc:fabric-loader:0.15.11",
                libraries.get(1).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void resultIsFlatAndCarriesNoInheritsFrom(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "1.20.1", "{\"id\":\"1.20.1\",\"mainClass\":\"Vanilla\"}");
        write(versionsDir, "profile", """
                {"id":"profile","inheritsFrom":"1.20.1","mainClass":"Loader"}
                """);

        JsonObject flat = new VersionResolver(versionsDir).resolve("profile");

        // A leftover inheritsFrom would make the downloader re-resolve forever.
        assertFalse(flat.has("inheritsFrom"));
    }

    @Test
    void deeperChainResolvesInOrder(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "base", """
                {"id":"base","mainClass":"Base","libraries":[{"name":"a:a:1"}]}
                """);
        write(versionsDir, "middle", """
                {"id":"middle","inheritsFrom":"base","libraries":[{"name":"b:b:1"}]}
                """);
        write(versionsDir, "top", """
                {"id":"top","inheritsFrom":"middle","libraries":[{"name":"c:c:1"}]}
                """);

        JsonObject flat = new VersionResolver(versionsDir).resolve("top");

        assertEquals(3, flat.getAsJsonArray("libraries").size());
        assertEquals("a:a:1",
                flat.getAsJsonArray("libraries").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("c:c:1",
                flat.getAsJsonArray("libraries").get(2).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void duplicateCoordinatesKeepTheDerivedVersion(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "1.20.1", """
                {"id":"1.20.1","mainClass":"Vanilla",
                 "libraries":[{"name":"org.ow2.asm:asm:9.5"}]}
                """);
        write(versionsDir, "profile", """
                {"id":"profile","inheritsFrom":"1.20.1",
                 "libraries":[{"name":"org.ow2.asm:asm:9.6"}]}
                """);

        JsonObject flat = new VersionResolver(versionsDir).resolve("profile");
        JsonArray libraries = flat.getAsJsonArray("libraries");

        // A loader and its base version ship the same library at different versions; keeping the
        // old one would put the wrong jar on the classpath.
        assertEquals(1, libraries.size());
        assertEquals("org.ow2.asm:asm:9.6",
                libraries.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void argumentsAreConcatenatedBaseFirst(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "1.20.1", """
                {"id":"1.20.1","mainClass":"Vanilla",
                 "arguments":{"jvm":[{"value":"-Xmx1G"}],"game":[{"value":"--base"}]}}
                """);
        write(versionsDir, "profile", """
                {"id":"profile","inheritsFrom":"1.20.1",
                 "arguments":{"game":[{"value":"--loader"}]}}
                """);

        JsonObject flat = new VersionResolver(versionsDir).resolve("profile");

        // The loader's args assume the base ones are already there.
        JsonArray game = flat.getAsJsonObject("arguments").getAsJsonArray("game");
        assertEquals(2, game.size());
        assertEquals("--base", game.get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("--loader", game.get(1).getAsJsonObject().get("value").getAsString());
        assertEquals(1, flat.getAsJsonObject("arguments").getAsJsonArray("jvm").size());
    }

    @Test
    void inheritanceCycleIsRejected(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "a", "{\"id\":\"a\",\"inheritsFrom\":\"b\"}");
        write(versionsDir, "b", "{\"id\":\"b\",\"inheritsFrom\":\"a\"}");

        IOException error = assertThrows(IOException.class,
                () -> new VersionResolver(versionsDir).resolve("a"));
        assertTrue(error.getMessage().contains("Цикл"), error.getMessage());
    }

    @Test
    void missingParentIsReported(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "profile", "{\"id\":\"profile\",\"inheritsFrom\":\"1.7.10\"}");

        assertThrows(IOException.class, () -> new VersionResolver(versionsDir).resolve("profile"));
    }

    @Test
    void existsReportsOnlyRealVersionFiles(@TempDir Path versionsDir) throws IOException {
        VersionResolver resolver = new VersionResolver(versionsDir);
        assertFalse(resolver.exists("1.20.1"));

        write(versionsDir, "1.20.1", "{\"id\":\"1.20.1\"}");
        assertTrue(resolver.exists("1.20.1"));
        assertFalse(resolver.exists("  "));
        assertFalse(resolver.exists(null));
    }

    @Test
    void inheritedClientJarPathStaysOnTheVanillaVersion(@TempDir Path versionsDir) throws IOException {
        // Mojang no longer publishes downloads.client.path, so the launcher has to supply it. Doing
        // that with the profile's own id would download a second copy of the client jar.
        write(versionsDir, "1.20.1", """
                {"id":"1.20.1","mainClass":"Vanilla",
                 "downloads":{"client":{"url":"https://example.invalid/client.jar",
                                       "sha1":"abc"}}}
                """);
        write(versionsDir, "fabric-1.20.1", """
                {"id":"fabric-1.20.1","inheritsFrom":"1.20.1","mainClass":"Loader"}
                """);

        JsonObject flat = new VersionResolver(versionsDir).resolve("fabric-1.20.1");
        JsonObject client = flat.getAsJsonObject("downloads").getAsJsonObject("client");

        assertEquals("1.20.1/1.20.1.jar", client.get("path").getAsString());
    }

    @Test
    void publishedClientPathIsLeftAlone(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "1.20.1", """
                {"id":"1.20.1","mainClass":"Vanilla",
                 "downloads":{"client":{"url":"https://example.invalid/c.jar",
                                       "path":"custom/renamed.jar"}}}
                """);

        JsonObject flat = new VersionResolver(versionsDir).resolve("1.20.1");
        assertEquals("custom/renamed.jar", flat.getAsJsonObject("downloads")
                .getAsJsonObject("client").get("path").getAsString());
    }

    @Test
    void clientJarResolvesToTheSharedVanillaFile(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "1.20.1", """
                {"id":"1.20.1","mainClass":"Vanilla",
                 "downloads":{"client":{"url":"https://example.invalid/client.jar"}}}
                """);
        write(versionsDir, "fabric-1.20.1", """
                {"id":"fabric-1.20.1","inheritsFrom":"1.20.1","mainClass":"Loader"}
                """);

        VersionResolver resolver = new VersionResolver(versionsDir);
        VersionMetadata metadata =
                VersionMetadata.fromJson(resolver.resolve("fabric-1.20.1"));

        // The profile owns no jar of its own, so the classpath has to point at the base version's.
        assertEquals(versionsDir.resolve("1.20.1").resolve("1.20.1.jar"),
                GameDownloader.clientJar(metadata, versionsDir));
    }

    @Test
    void vanillaClientJarKeepsItsOwnId(@TempDir Path versionsDir) throws IOException {
        write(versionsDir, "1.21.1", """
                {"id":"1.21.1","mainClass":"Vanilla",
                 "downloads":{"client":{"url":"https://example.invalid/client.jar"}}}
                """);

        VersionMetadata metadata = VersionMetadata.fromJson(
                new VersionResolver(versionsDir).resolve("1.21.1"));

        assertEquals(versionsDir.resolve("1.21.1").resolve("1.21.1.jar"),
                GameDownloader.clientJar(metadata, versionsDir));
    }

    private static void write(Path versionsDir, String id, String json) throws IOException {
        Path dir = versionsDir.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), json, StandardCharsets.UTF_8);
    }
}
