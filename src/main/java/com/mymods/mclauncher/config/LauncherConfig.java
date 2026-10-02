package com.mymods.mclauncher.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

/** Persistent launcher settings, stored as JSON next to the launcher data folder. */
public final class LauncherConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Absolute path to the launcher working directory that holds versions, libraries and assets. */
    public String gameDir = defaultGameDir().toString();

    /** Whether the launcher keeps a copy of worlds, resource packs and other user content. */
    public boolean isolateGameDir = true;

    public int minMemoryMb = 512;
    public int maxMemoryMb = 4096;

    /** JVM options applied to every game launch, before the ones Mojang requires. */
    public String extraJvmArgs = "";

    /** Width and height requested from Minecraft on first start. */
    public int windowWidth = 1280;
    public int windowHeight = 720;

    /** Preferred Java executable for running Minecraft. Empty means "auto detect". */
    public String javaPath = "";

    /** Selected auth provider id: one of offline, ely, microsoft. */
    public String authProvider = "offline";

    /** Custom Yggdrasil-compatible API root, used by Ely and other Mojang-alternative services. */
    public String authServerUrl = "https://authserver.ely.by";

    /** Azure application (client) id used for the Microsoft device code flow. */
    public String microsoftClientId = "";

    /** Whether the launcher adds the authlib-injector java agent for Ely sessions. */
    public boolean useAuthlibInjector = true;

    /** Version id to preselect on the next start. Empty means "use the default release". */
    public String lastVersionId = "";

    /** Nickname typed in offline mode, so the next start does not ask for it again. */
    public String lastUsername = "";

    /**
     * What the launcher window does when it is closed while a game is running:
     * {@code close} exits, {@code hide} keeps it in the tray, {@code minimize} leaves it minimized.
     */
    public String closeAction = "close";

    public String appearanceTheme = "dark";
    public String appearanceAccent = "#63A86F";
    public int interfaceFontSize = 13;

    public String mainMenuWallpaper = "";

    private static Path defaultGameDir() {
        String home = System.getProperty("user.home", ".");
        return Path.of(home, "MCLauncher");
    }

    public static Path settingsFile(Path dataDir) {
        return dataDir.resolve("settings.json");
    }

    public static LauncherConfig load(Path dataDir) {
        Path file = settingsFile(dataDir);
        if (!Files.isRegularFile(file)) {
            return new LauncherConfig();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            LauncherConfig loaded = GSON.fromJson(reader, LauncherConfig.class);
            return loaded == null ? new LauncherConfig() : loaded;
        } catch (IOException | JsonSyntaxException e) {
            System.err.println("Failed to read settings, falling back to defaults: " + e.getMessage());
            return new LauncherConfig();
        }
    }

    public void save(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            Path file = settingsFile(dataDir);
            Path tmp = dataDir.resolve("settings.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Failed to save settings: " + e.getMessage());
        }
    }

    /** Root of the launcher-owned Minecraft installation. */
    public Path installRoot() {
        return Path.of(gameDir).resolve(".mclauncher");
    }

    public Path versionsDir() {
        return installRoot().resolve("versions");
    }

    public Path librariesDir() {
        return installRoot().resolve("libraries");
    }

    public Path assetsDir() {
        return installRoot().resolve("assets");
    }

    public Path nativesDir() {
        return installRoot().resolve("natives");
    }

    public Path authlibJar() {
        return installRoot().resolve("authlib-injector.jar");
    }

    public Path tokensFile() {
        return installRoot().resolve("tokens.json");
    }
}