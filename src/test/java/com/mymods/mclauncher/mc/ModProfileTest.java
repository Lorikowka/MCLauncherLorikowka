package com.mymods.mclauncher.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModProfileTest {

    @Test
    void idIsReadableAndUniquePerLoaderRelease() {
        assertEquals("fabric-1.20.1-0.15.11",
                ModProfile.buildId(ModLoaderType.FABRIC, "1.20.1", "0.15.11"));
        assertEquals("1.20.1", ModProfile.buildId(ModLoaderType.VANILLA, "1.20.1", "0.15.11"));
        // Same Minecraft version, different loader: the ids must not collide.
        assertFalse(ModProfile.buildId(ModLoaderType.FABRIC, "1.20.1", "0.15.11")
                .equals(ModProfile.buildId(ModLoaderType.FORGE, "1.20.1", "0.15.11")));
    }

    @Test
    void idOmitsMissingLoaderVersion() {
        // Forge and NeoForge name their versions the way their installers do, with the Minecraft
        // version first, so the loader never has to invent a different name for them.
        assertEquals("1.20.1-forge", ModProfile.buildId(ModLoaderType.FORGE, "1.20.1", null));
        assertEquals("1.20.1-forge", ModProfile.buildId(ModLoaderType.FORGE, "1.20.1", "  "));
        assertEquals("fabric-1.20.1", ModProfile.buildId(ModLoaderType.FABRIC, "1.20.1", null));
    }

    @Test
    void forgeIdMatchesWhatItsInstallerWrites() {
        assertEquals("1.20.1-forge-47.4.23",
                ModProfile.buildId(ModLoaderType.FORGE, "1.20.1", "47.4.23"));
        assertEquals("1.20.1-neoforge-20.4.251",
                ModProfile.buildId(ModLoaderType.NEOFORGE, "1.20.1", "20.4.251"));
    }

    @Test
    void defaultDisplayNameMentionsLoaderAndVersion() {
        ModProfile fabric = new ModProfile("fabric-1.20.1-0.15.11", "1.20.1", ModLoaderType.FABRIC,
                "0.15.11", List.of(), null);
        assertEquals("1.20.1 · Fabric 0.15.11", fabric.displayName());
        assertEquals("1.20.1", ModProfile.vanilla("1.20.1").displayName());
    }

    @Test
    void explicitDisplayNameIsKept() {
        ModProfile named = new ModProfile("x", "1.20.1", ModLoaderType.FABRIC, "0.15.11",
                List.of(), "Мой сборка");
        assertEquals("Мой сборка", named.displayName());
    }

    @Test
    void modListIsImmutableAndNullTolerant() {
        ModProfile profile = new ModProfile("x", "1.20.1", ModLoaderType.FABRIC, "0.15.11", null, null);
        assertEquals(List.of(), profile.modIds());
    }

    @Test
    void onlyNonVanillaProfilesHoldMods() {
        assertFalse(ModProfile.vanilla("1.20.1").moddable());
        assertTrue(new ModProfile("x", "1.20.1", ModLoaderType.FABRIC, "0.15.11", List.of(), null)
                .moddable());
    }

    @Test
    void sameInstallIgnoresNameAndMods() {
        ModProfile a = new ModProfile("fabric-1.20.1-0.15.11", "1.20.1", ModLoaderType.FABRIC,
                "0.15.11", List.of("a.jar"), "Сборка 1");
        ModProfile b = new ModProfile("fabric-1.20.1-0.15.11", "1.20.1", ModLoaderType.FABRIC,
                "0.15.11", List.of("b.jar", "c.jar"), "Сборка 2");
        ModProfile other = new ModProfile("fabric-1.20.1-0.16.0", "1.20.1", ModLoaderType.FABRIC,
                "0.16.0", List.of(), null);

        assertTrue(a.sameInstallAs(b));
        assertFalse(a.sameInstallAs(other));
        assertFalse(a.sameInstallAs(null));
    }
}
