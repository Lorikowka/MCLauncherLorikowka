package com.mymods.mclauncher.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class ModLoaderCatalogTest {

    /**
     * A Forge build number is digits and dots, and the dash belongs to the Minecraft version.
     *
     * <p>Filtering Forge versions with the same "contains no dash" rule used for NeoForge rejects
     * every one of them, which looks like "Forge has no builds for 1.20.1" rather than a bad filter.
     */
    @Test
    void forgeBuildFilterKeepsStableBuildsAndDropsSuffixes() {
        assertTrue(PLAIN.matcher("47.4.23").matches());
        assertTrue(PLAIN.matcher("47.2.0").matches());
        assertTrue(PLAIN.matcher("52").matches());

        // Pre-release suffixes, and the trailing Minecraft tag older Forge versions carry.
        assertFalse(PLAIN.matcher("47.4.0-beta").matches());
        assertFalse(PLAIN.matcher("10.13.4.1614-1.7.10").matches());
        assertFalse(PLAIN.matcher("").matches());
    }

    @Test
    void buildsAreComparedNumericallyNotAlphabetically() {
        // The trap this guards: as text "47.4.23" sorts BELOW "47.4.9", so the newest Forge build
        // for 1.20.1 would end up at the bottom of the build picker.
        assertTrue(ModLoaderCatalog.compareVersions("47.4.23", "47.4.9") > 0);
        assertTrue(ModLoaderCatalog.compareVersions("52.1.10", "52.1.2") > 0);
        assertTrue(ModLoaderCatalog.compareVersions("20.4.251", "20.4.99") > 0);
        assertTrue(ModLoaderCatalog.compareVersions("47.4.9", "47.4.23") < 0);
        assertEquals(0, ModLoaderCatalog.compareVersions("47.4.23", "47.4.23"));
    }

    @Test
    void neoForgeLineIsDerivedFromTheMinecraftVersion() {
        // The rule the launcher used to hardcode as a table. Deriving it means a Minecraft release
        // newer than the table needs no code change to be recognised.
        assertEquals("20.4.", ModLoaderCatalog.neoForgeLine("1.20.4"));
        assertEquals("20.2.", ModLoaderCatalog.neoForgeLine("1.20.2"));
        assertEquals("21.1.", ModLoaderCatalog.neoForgeLine("1.21.1"));
        assertEquals("21.8.", ModLoaderCatalog.neoForgeLine("1.21.8"));

        // A two-part version has no patch to copy and was released as the .0 line.
        assertEquals("21.0.", ModLoaderCatalog.neoForgeLine("1.21"));

        // NeoForge took over from Forge at 1.20.2, so this line was never published. Deriving it is
        // what turns "no such loader version" into an honest empty list instead of a broken map.
        assertEquals("20.1.", ModLoaderCatalog.neoForgeLine("1.20.1"));
    }

    @Test
    void neoForgeLineSurvivesVersionsItWasNeverWrittenFor() {
        // These are what a hand-written table gets wrong: they are simply absent from it, so the
        // launcher would report an unsupported Minecraft version even after NeoForge ships builds.
        assertEquals("21.9.", ModLoaderCatalog.neoForgeLine("1.21.9"));
        assertEquals("22.0.", ModLoaderCatalog.neoForgeLine("1.22"));

        // Anything unexpected still yields a prefix to probe rather than throwing.
        assertEquals("24.2.", ModLoaderCatalog.neoForgeLine("1.24.2"));
        assertEquals("nightly", ModLoaderCatalog.neoForgeLine("nightly"));
    }

    /**
     * A loader is identified by the maven group of the library its installer writes.
     *
     * <p>That group is the only part of an installed profile that the launcher did not write
     * itself, so it is the part worth reading. Recognising a profile by its libraries also means one
     * installed by another launcher, or by hand, shows up with the right type instead of vanishing
     * for want of a marker only this launcher knows about.
     */
@Test
    void loaderIsDetectedFromTheLibraryTheInstallerWrote() {
        assertEquals(ModLoaderType.FORGE, detect("net.minecraftforge:forge:1.20.1-47.4.23:universal"));
        assertEquals(ModLoaderType.NEOFORGE, detect("net.neoforged:neoforge:20.4.251:universal"));
        assertEquals(ModLoaderType.FABRIC, detect("net.fabricmc:fabric-loader:0.16.9"));
    }

    /**
     * Two loader groups at once means the profile is not something to guess at.
     *
     * <p>A wrong answer here is worse than no answer: the profile lands in the build picker as a
     * loader that cannot be installed. Refusing lets the caller fall back to what it wrote itself.
     */
    @Test
    void loaderIsNotGuessedWhenTwoGroupsArePresent() {
        assertNull(detect("net.minecraftforge:forge:1.20.1-47.4.23",
                "net.neoforged:neoforge:20.4.251"));
        assertNull(detect("net.fabricmc:fabric-loader:0.16.9",
                "net.minecraftforge:forge:1.20.1-47.4.23"));
    }

    @Test
    void aVersionWithNoLoaderLibraryIsNotAProfile() {
        assertNull(detect("com.mojang:logging:1.1.1", "org.ow2.asm:asm:9.5"));
        assertNull(detect("net.lwjgl:lwjgl:3.3.1:linux"));

        // A malformed coordinate belongs to no group and must not throw.
        assertNull(detect("guava"));
        assertNull(detect(":no-group"));
    }

    private static ModLoaderType detect(String... coordinates) {
        JsonObject json = new JsonObject();
        com.google.gson.JsonArray libraries = new com.google.gson.JsonArray();
        for (String coordinate : coordinates) {
            JsonObject library = new JsonObject();
            library.addProperty("name", coordinate);
            libraries.add(library);
        }
        json.add("libraries", libraries);
        return ModLoaderCatalog.detectFromLibraries(json);
    }

    private static final java.util.regex.Pattern PLAIN = java.util.regex.Pattern.compile("\\d+(\\.\\d+)*");
}