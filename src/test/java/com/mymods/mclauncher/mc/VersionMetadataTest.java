package com.mymods.mclauncher.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class VersionMetadataTest {

    private static final String OLD_STYLE = """
            {
              "name": "org.lwjgl:lwjgl:3.2.2",
              "natives": {"windows": "natives-windows"},
              "downloads": {
                "artifact": {"path": "org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2.jar",
                             "url": "https://libraries.minecraft.net/lwjgl-3.2.2.jar",
                             "sha1": "aaaaaaaa"},
                "classifiers": {"natives-windows": {
                    "path": "org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2-natives-windows.jar",
                    "url": "https://libraries.minecraft.net/lwjgl-3.2.2-natives-windows.jar",
                    "sha1": "bbbbbbbb"}}
              }
            }
            """;

    private static final String MODERN_STYLE = """
            {
              "name": "org.lwjgl:lwjgl:3.3.1:natives-windows",
              "downloads": {
                "artifact": {"path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.jar",
                             "url": "https://libraries.minecraft.net/natives.jar",
                             "sha1": "cccccccc"}
              }
            }
            """;

    private static final String PLAIN = """
            {"name": "com.example:core:1.0",
             "downloads": {"artifact": {"path": "com/example/core/1.0/core-1.0.jar",
                                        "url": "https://example/core-1.0.jar",
                                        "sha1": "dddddddd"}}}
            """;

    private static VersionMetadata metadataOf(String... libraries) {
        JsonObject json = new JsonObject();
        json.addProperty("id", "test");
        json.addProperty("mainClass", "TestMain");
        JsonArray array = new JsonArray();
        for (String library : libraries) {
            array.add(JsonParser.parseString(library));
        }
        json.add("libraries", array);
        return VersionMetadata.fromJson(json);
    }

    @Test
    void oldStyleNativesEntryKeepsItsMainJarOnTheDownloadList() {
        VersionMetadata metadata = metadataOf(OLD_STYLE, PLAIN);
        assertEquals(2, metadata.classpathLibraries().size(),
                "the main jar of a natives-mapped entry still has to be downloaded");
        assertEquals(1, metadata.nativeLibraries().size());
        Library natives = metadata.nativeLibraries().get(0);
        assertEquals("org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2-natives-windows.jar", natives.nativesPath());
        assertEquals("https://libraries.minecraft.net/lwjgl-3.2.2-natives-windows.jar",
                natives.nativesUrl());
        assertEquals("bbbbbbbb", natives.nativesSha1());
    }

    @Test
    void modernNativesEntryDownloadsOnlyAsANativesJar() {
        VersionMetadata metadata = metadataOf(MODERN_STYLE, PLAIN);
        assertEquals(1, metadata.classpathLibraries().size(),
                "a 1.13+ natives entry contributes no main jar");
        assertEquals("com.example:core:1.0", metadata.classpathLibraries().get(0).name());
        assertEquals(1, metadata.nativeLibraries().size());
        assertFalse(metadata.nativeLibraries().get(0).hasNativesMap());
    }

    @Test
    void nativesMapLibraryExposesClassifierHelpers() {
        Library library = Library.fromJson(JsonParser.parseString(OLD_STYLE).getAsJsonObject());
        assertTrue(library.hasNativesMap());
        assertTrue(library.isNativeArtifact());
        assertEquals("https://libraries.minecraft.net/lwjgl-3.2.2.jar", library.artifactUrl());
        assertEquals("org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2.jar", library.path());
    }
}
