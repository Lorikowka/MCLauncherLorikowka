package com.mymods.mclauncher.mc;

import java.util.Locale;

/**
 * Mod loaders the launcher knows how to set up.
 *
 * <p>Each one publishes a version JSON that inherits from vanilla, so the only thing that differs
 * is where the metadata comes from and whether an installer has to be executed first.
 */
public enum ModLoaderType {

    /** No loader: the vanilla version as published by Mojang. */
    VANILLA("Ванильный", false, false),

    /**
     * Fabric. Its meta API serves the libraries and the launch entry point directly, so no
     * installer has to be executed.
     */
    FABRIC("Fabric", true, false),

    /**
     * Forge and NeoForge ship a Gradle-built installer jar. Running it produces the version JSON,
     * so these need an installer step and a JVM before anything can be assembled.
     */
    FORGE("Forge", true, true),
    NEOFORGE("NeoForge", true, true);

    private final String displayName;
    private final boolean moddable;
    private final boolean needsInstaller;

    ModLoaderType(String displayName, boolean moddable, boolean needsInstaller) {
        this.displayName = displayName;
        this.moddable = moddable;
        this.needsInstaller = needsInstaller;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether this loader can load mods at all, as opposed to plain vanilla. */
    public boolean moddable() {
        return moddable;
    }

    /** Whether setting the loader up means executing an installer jar. */
    public boolean needsInstaller() {
        return needsInstaller;
    }

    public static ModLoaderType parse(String id) {
        if (id == null) {
            return VANILLA;
        }
        for (ModLoaderType type : values()) {
            if (type.name().equalsIgnoreCase(id)) {
                return type;
            }
        }
        return VANILLA;
    }

    /** Lowercase identifier for file names. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
