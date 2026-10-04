package com.mymods.mclauncher.ui;

import java.nio.file.Path;

/**
 * The banner appearance chosen for one build.
 *
 * <p>{@code mode} mirrors the mockup's three segments: cycle the instance's screenshots, draw one
 * of the six preset scenes, or stretch a custom image over the cover.
 */
public record BannerData(String mode, int preset, Path image, boolean auto) {

    public static final String MODE_SHOTS = "shots";
    public static final String MODE_PRESET = "preset";
    public static final String MODE_CUSTOM = "custom";
}
