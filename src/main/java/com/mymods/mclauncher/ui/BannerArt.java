package com.mymods.mclauncher.ui;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * The six banner preset scenes from the mockup, drawn with Java2D instead of SVG.
 *
 * <p>Each scene is a sky gradient plus three terraced ground layers whose heights wobble like the
 * original's sine curve; the seed comes from the build id so two builds do not look identical.
 */
public final class BannerArt {

    public static final String[] NAMES = {
        "Равнины", "Пещера", "Нижний мир", "Энд", "Океан", "Ночь"
    };

    private static final String[][] SKY = {
        {"#6fb0ff", "#c4e6ff"},
        {"#17171c", "#25252d"},
        {"#380a0a", "#7a1c12"},
        {"#150a24", "#2c1650"},
        {"#2a7fd6", "#8ccaff"},
        {"#0a1030", "#1c2a5c"},
    };

    private static final String[][] GROUND = {
        {"#6aa84f", "#4f8a3a", "#3b6b2b"},
        {"#3a3a42", "#2c2c34", "#1f1f26"},
        {"#5a1a1a", "#401010", "#2a0909"},
        {"#d9d79a", "#b8b67a", "#8c8a58"},
        {"#1f6fb5", "#175a96", "#10456f"},
        {"#2e5a3a", "#244a2e", "#1a3822"},
    };

    private static final String[] SUN = {"#fff3a0", null, null, null, "#ffffff", "#e8ecff"};

    private static final Map<Integer, Image> CACHE = new HashMap<>();

    private BannerArt() {
    }

    /** The scene at {@code preset} (mod six) with terrain seeded from the build id. */
    public static Image image(int preset, int seed) {
        int p = Math.floorMod(preset, NAMES.length);
        int s = Math.floorMod(seed, 7);
        return CACHE.computeIfAbsent(p * 16 + s, key -> render(p, s));
    }

    private static Image render(int preset, int seed) {
        int width = 1000;
        int height = 300;
        BufferedImage pixels = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = pixels.createGraphics();

        g.setPaint(new GradientPaint(0, 0, awt(SKY[preset][0]), 0, height, awt(SKY[preset][1])));
        g.fillRect(0, 0, width, height);

        if (preset == 3 || preset == 5) {
            g.setColor(new Color(255, 255, 255, preset == 3 ? 200 : 140));
            for (int i = 0; i < 22; i++) {
                g.fillRect((i * 137 + seed * 53) % 990, (i * 61 + seed * 29) % 130, 6, 6);
            }
        }

        if (SUN[preset] != null) {
            g.setColor(awt(SUN[preset]));
            g.fillRect(560 + Math.floorMod(seed * 70, 300), 40, 48, 48);
        }

        double sd = seed;
        for (int layer = 0; layer < 3; layer++) {
            g.setColor(awt(GROUND[preset][layer]));
            for (int i = 0; i < 32; i++) {
                double wave = Math.sin(i * (0.4 + layer * 0.25) + sd + layer * 2) * 2
                        + Math.sin(i * 1.3 + sd);
                int y = (int) Math.round(165 + layer * 40 + wave * 20);
                g.fillRect(i * 32, y, 33, height - y);
            }
        }

        if (preset == 2) {
            g.setColor(awt("#ff7a1a"));
            for (int i = Math.floorMod(seed, 3); i < 32; i += 5) {
                g.fillRect(i * 32, 268, 33, 32);
            }
        }
        if (preset == 1) {
            g.setColor(awt("#d09a42"));
            for (int i = Math.floorMod(seed, 4); i < 32; i += 4) {
                g.fillRect(i * 32 + 8, 250 + (i * 7) % 40, 16, 16);
            }
        }
        g.dispose();

        WritableImage image = new WritableImage(width, height);
        image.getPixelWriter().setPixels(0, 0, width, height,
            PixelFormat.getIntArgbInstance(), IntBuffer.wrap(
                pixels.getRGB(0, 0, width, height, null, 0, width)), width);
        return image;
    }

    private static Color awt(String hex) {
        return new Color(Integer.parseInt(hex.substring(1), 16));
    }
}
