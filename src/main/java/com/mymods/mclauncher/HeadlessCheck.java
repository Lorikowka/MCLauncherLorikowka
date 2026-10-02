package com.mymods.mclauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.mymods.mclauncher.auth.AuthSession;
import com.mymods.mclauncher.auth.OfflineSession;
import com.mymods.mclauncher.config.LauncherConfig;
import com.mymods.mclauncher.mc.GameDownloader;
import com.mymods.mclauncher.mc.JavaRuntimes;
import com.mymods.mclauncher.mc.McVersion;
import com.mymods.mclauncher.mc.VersionCatalog;
import com.mymods.mclauncher.mc.VersionMetadata;

/**
 * Headless end-to-end check: downloads one real version and prints the command that would run.
 *
 * <p>Run with {@code gradlew headlessCheck -Pversion=1.20.1}. Useful for verifying the install
 * pipeline without opening the UI.
 */
public final class HeadlessCheck {

    public static void main(String[] args) throws Exception {
        String versionId = args.length > 0 ? args[0] : "1.20.1";
        Path gameDir = Path.of(System.getProperty("user.home"), "MCLauncherHeadlessCheck");

        LauncherConfig config = LauncherConfig.load(gameDir);
        config.gameDir = gameDir.toString();

        VersionCatalog catalog = new VersionCatalog();
        catalog.refresh();
        McVersion version = catalog.find(versionId);
        if (version == null) {
            throw new IllegalArgumentException("No such version: " + versionId);
        }
        System.out.println("Version: " + version.id() + " (" + version.type() + ")");

        JsonObject versionJson = catalog.loadVersionJson(version, config.versionsDir());
        VersionMetadata metadata = VersionMetadata.fromJson(versionJson);
        System.out.println("Main class: " + metadata.mainClass);
        System.out.println("Requires Java: " + metadata.requiredJavaMajor());
        System.out.println("Libraries: " + metadata.classpathLibraries().size()
                + " (+" + metadata.nativeLibraries().size() + " with natives)");

        Consumer<String> log = System.out::println;
        GameDownloader downloader = new GameDownloader(config.versionsDir(), config.librariesDir(),
                config.assetsDir(), config.nativesDir(), log);

        downloader.install(metadata, versionJson);
        System.out.println("Install finished for " + versionId);

        JavaRuntimes.LauncherSettings settings = new JavaRuntimes.LauncherSettings(
                config.javaPath, config.installRoot().resolve("runtimes"));
        JavaRuntimes.Runtime runtime = JavaRuntimes.resolve(settings, metadata.requiredJavaMajor(), log);

        AuthSession session = new OfflineSession("HeadlessTester");
        Path versionDir = config.versionsDir().resolve(version.id());
        List<String> command = com.mymods.mclauncher.mc.CommandBuilder.build(runtime.java(), metadata,
                new com.mymods.mclauncher.mc.CommandBuilder.Context(
                        gameDir,
                        config.assetsDir(),
                        metadata.assetsIndexId,
                        versionDir,
                        config.librariesDir(),
                        config.nativesDir().resolve(version.id()),
                        session,
                        metadata.id,
                        metadata.type,
                        metadata.mainClass,
                        config.windowWidth,
                        config.windowHeight,
                        "",
                        List.of("-Xmx" + config.maxMemoryMb + "M"),
                        metadata.libraries,
                        com.mymods.mclauncher.mc.GameDownloader.clientJar(metadata,
                                config.versionsDir())),
                false,
                config.authlibJar());

        System.out.println("\nJava: " + runtime.java());
        System.out.println("\nCommand:");
        command.forEach(part -> System.out.println("  " + part));
    }
}