package com.mymods.mclauncher.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-build banner appearance, kept in {@code banners.json} next to {@code settings.json}.
 *
 * <p>One entry per build id, so a pack keeps its chosen cover while vanilla builds fall back to
 * the defaults the file simply does not mention.
 */
public final class BannerStore {

    /** One build's choice; the defaults match the mockup (screenshots, auto-rotate on). */
    public static final class Entry {
        public String mode = "shots";
        public int preset = 0;
        public String image = "";
        public boolean auto = true;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dataDir;
    private final Map<String, Entry> entries = new HashMap<>();

    private BannerStore(Path dataDir) {
        this.dataDir = dataDir;
    }

    public static BannerStore load(Path dataDir) {
        BannerStore store = new BannerStore(dataDir);
        Path file = dataDir.resolve("banners.json");
        if (Files.isRegularFile(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<String, Entry> loaded = GSON.fromJson(reader,
                        new TypeToken<Map<String, Entry>>() { }.getType());
                if (loaded != null) {
                    store.entries.putAll(loaded);
                }
            } catch (Exception e) {
                System.err.println("Failed to load banners: " + e.getMessage());
            }
        }
        return store;
    }

    /** The entry for a build, created with defaults on first touch. */
    public Entry forId(String id) {
        return entries.computeIfAbsent(id == null ? "" : id, key -> new Entry());
    }

    public void save() {
        try {
            Files.createDirectories(dataDir);
            Path file = dataDir.resolve("banners.json");
            Path tmp = dataDir.resolve("banners.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(entries, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("Failed to save banners: " + e.getMessage());
        }
    }
}
