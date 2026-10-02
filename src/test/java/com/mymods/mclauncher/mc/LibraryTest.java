package com.mymods.mclauncher.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class LibraryTest {

    private Library parse(String json) {
        return Library.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void libraryWithoutRulesAlwaysApplies() {
        Library library = parse("""
                {
                  "name": "com.example:plain:1.0"
                }
                """);
        assertTrue(library.appliesHere());
    }

    @Test
    void deriveMavenPathFromCoordinatesWhenNoDownloadInfo() {
        Library library = parse("""
                {
                  "name": "org.lwjgl:lwjgl:3.3.3"
                }
                """);
        assertEquals("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar", library.path());
    }

    @Test
    void disallowRuleVetoesLibrary() {
        JsonObject json = JsonParser.parseString("""
                {
                  "name": "org.lwjgl:natives:1.0",
                  "rules": [
                    {"action": "allow"},
                    {"action": "disallow", "os": {"name": "__never__"}}
                  ]
                }
                """).getAsJsonObject();
        assertTrue(Library.fromJson(json).appliesHere());

        json = JsonParser.parseString("""
                {
                  "name": "org.lwjgl:natives:1.0",
                  "rules": [
                    {"action": "allow", "os": {"name": "__never__"}},
                    {"action": "disallow", "os": {"name": "windows"}}
                  ]
                }
                """).getAsJsonObject();
        assertFalse(Library.fromJson(json).appliesHere());
    }

    @Test
    void artifactUrlAndShaComeFromDownloads() {
        Library library = parse("""
                {
                  "name": "com.example:thing:2.0",
                  "downloads": {
                    "artifact": {"path": "com/example/thing/2.0/thing-2.0.jar",
                                 "url": "https://libraries.minecraft.net/thing-2.0.jar",
                                 "sha1": "0123456789abcdef"}
                  }
                }
                """);
        assertEquals("https://libraries.minecraft.net/thing-2.0.jar", library.artifactUrl());
        assertEquals("0123456789abcdef", library.artifactSha1());
    }

    @Test
    void modernNativeEntryIsItsOwnLibrary() {
        Library library = parse("""
                {
                  "name": "org.lwjgl:lwjgl:3.3.1:natives-windows",
                  "rules": [{"action": "allow", "os": {"name": "windows"}}],
                  "downloads": {
                    "artifact": {
                      "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.jar",
                      "url": "https://libraries.minecraft.net/lwjgl-natives-windows.jar",
                      "sha1": "abcdef0123456789"
                    }
                  }
                }
                """);
        assertTrue(library.isNativeArtifact());
        assertFalse(library.natives(), "1.13+ entries have no natives map");
        assertEquals("org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.jar", library.nativesPath());
        assertEquals("https://libraries.minecraft.net/lwjgl-natives-windows.jar", library.artifactUrl());
    }

    @Test
    void plainLibraryIsNotTreatedAsNative() {
        assertFalse(parse("""
                {"name": "org.lwjgl:lwjgl:3.3.1"}
                """).isNativeArtifact());
    }

    @Test
    void nativesPathSwapsJarSuffix() {
        Library library = parse("""
                {
                  "name": "org.lwjgl:lwjgl:3.3.3",
                  "natives": {"windows": "natives-windows", "linux": "natives-linux"},
                  "downloads": {
                    "classifiers": {
                      "natives-windows": {"url": "https://example/lwjgl-natives-windows.jar"}
                    }
                  }
                }
                """);
        assertTrue(library.natives());
        // The classifier is platform specific, so assert on the shared prefix only.
        String nativesPath = library.nativesPath();
        assertTrue(nativesPath.startsWith("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-"),
                "unexpected natives path: " + nativesPath);
        assertTrue(nativesPath.endsWith(".jar"));
    }
}