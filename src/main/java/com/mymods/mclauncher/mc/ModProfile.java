package com.mymods.mclauncher.mc;

import java.util.List;
import java.util.Objects;

/**
 * One installable combination of Minecraft version, mod loader and mods.
 *
 * <p>A profile owns a version id in the launcher, so switching between two modded setups is just
 * choosing a different id. That is the same trick the official launcher uses and it keeps the
 * download and launch pipeline unaware of modding.
 *
 * @param id            version id written to {@code versions/<id>/}, unique per profile
 * @param baseVersion   vanilla version this profile extends, e.g. {@code 1.20.1}
 * @param loader        which loader to use
 * @param loaderVersion loader release, e.g. {@code 0.15.11} for Fabric
 * @param modIds        mod file names inside the profile's mods folder
 * @param displayName   what the user calls this setup in the UI
 */
public record ModProfile(String id, String baseVersion, ModLoaderType loader, String loaderVersion,
                         List<String> modIds, String displayName) {

    public ModProfile {
        Objects.requireNonNull(baseVersion, "baseVersion");
        Objects.requireNonNull(loader, "loader");
        modIds = modIds == null ? List.of() : List.copyOf(modIds);
        displayName = displayName == null || displayName.isBlank()
                ? defaultDisplayName(baseVersion, loader, loaderVersion)
                : displayName;
    }

    /** Vanilla profile for a base version, used when no modding is wanted. */
    public static ModProfile vanilla(String baseVersion) {
        return new ModProfile(baseVersion, baseVersion, ModLoaderType.VANILLA, null, List.of(),
                baseVersion);
    }

    private static String defaultDisplayName(String baseVersion, ModLoaderType loader,
                                            String loaderVersion) {
        if (loader == ModLoaderType.VANILLA) {
            return baseVersion;
        }
        return baseVersion + " · " + loader.displayName()
                + (loaderVersion == null || loaderVersion.isBlank() ? "" : " " + loaderVersion);
    }

    /** True when this profile can hold mods, i.e. it is not plain vanilla. */
    public boolean moddable() {
        return loader.moddable();
    }

    /**
     * Human-readable id, e.g. {@code fabric-1.20.1-0.15.11} or {@code 1.20.1-forge-47.4.23}.
     *
     * <p>Dots are legal in directory names on every platform this launcher targets, so the id is
     * kept readable rather than sanitised into something opaque. Forge and NeoForge choose this
     * name inside their own installers, so the launcher has to build it the same way or it will
     * look for a profile that was written under a different name.
     */
    public static String buildId(ModLoaderType loader, String baseVersion, String loaderVersion) {
        if (loader == ModLoaderType.VANILLA) {
            return baseVersion;
        }
        return ModLoaderCatalog.profileId(loader, baseVersion, loaderVersion);
    }

    /** Whether two profiles point at the same install, ignoring the cosmetic name and mods. */
    public boolean sameInstallAs(ModProfile other) {
        return other != null
                && id.equals(other.id)
                && baseVersion.equals(other.baseVersion)
                && loader == other.loader
                && Objects.equals(loaderVersion, other.loaderVersion);
    }
}
