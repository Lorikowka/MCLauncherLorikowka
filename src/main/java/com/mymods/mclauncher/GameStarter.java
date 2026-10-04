package com.mymods.mclauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;
import com.mymods.mclauncher.auth.AuthSession;
import com.mymods.mclauncher.auth.AuthlibInjector;
import com.mymods.mclauncher.auth.MicrosoftSession;
import com.mymods.mclauncher.config.LauncherConfig;
import com.mymods.mclauncher.mc.CommandBuilder;
import com.mymods.mclauncher.mc.GameDownloader;
import com.mymods.mclauncher.mc.GameProcess;
import com.mymods.mclauncher.mc.JavaRuntimes;
import com.mymods.mclauncher.mc.LaunchPreflight;
import com.mymods.mclauncher.mc.McVersion;
import com.mymods.mclauncher.mc.ModLoaderType;
import com.mymods.mclauncher.mc.ModProfile;
import com.mymods.mclauncher.mc.RuntimeInstaller;
import com.mymods.mclauncher.mc.VersionCatalog;
import com.mymods.mclauncher.mc.VersionMetadata;
import com.mymods.mclauncher.mc.VersionResolver;

/** Drives install-then-launch for one version, off the UI thread. */
public final class GameStarter {

    /** Callbacks used by the UI; all of them run on the calling background thread. */
    public interface Callbacks {
        void log(String line);

        void progress(int percent, String what);

        default void installed() {}

        default void failed(Exception e) {}

        /** The game process ended on its own, so the UI can offer "play" again. */
        default void exited(int code) {}
    }

    private final LauncherConfig config;
    private final VersionCatalog catalog;

    /** Set from the UI so a user can opt out of downloading a runtime. */
    public boolean autoInstallJava = true;

    public GameStarter(LauncherConfig config, VersionCatalog catalog) {
        this.config = config;
        this.catalog = catalog;
    }

    /** Installs the version if needed, then starts the game. Returns the live process. */
    public GameProcess launch(McVersion version, AuthSession session, Callbacks callbacks)
            throws IOException, InterruptedException {
        return launch(version, null, session, null, callbacks);
    }

    /**
     * Installs a version and starts it.
     *
     * @param profile mod loader profile to launch, or null for a plain vanilla version
     */
    public GameProcess launch(McVersion version, ModProfile profile, AuthSession session,
                              Callbacks callbacks)
            throws IOException, InterruptedException {
        return launch(version, profile, session, null, callbacks);
    }

    /** Installs a version and starts it, optionally joining a server through quick play. */
    public GameProcess launch(McVersion version, ModProfile profile, AuthSession session,
                              String quickPlayAddress, Callbacks callbacks)
            throws IOException, InterruptedException {

        JsonObject versionJson = resolveVersionJson(version, profile, callbacks);
        VersionMetadata metadata = VersionMetadata.fromJson(versionJson);
        if (quickPlayAddress != null && !quickPlayAddress.isBlank()
                && !metadata.supportsQuickPlayMultiplayer()) {
            throw new IOException("Эта версия Minecraft не поддерживает быстрое подключение. "
                    + "Выбери более новую версию.");
        }

        GameDownloader downloader = new GameDownloader(config.versionsDir(), config.librariesDir(),
                config.assetsDir(), config.nativesDir(), callbacks::log);
        downloader.onProgress(callbacks::progress);
        downloader.install(metadata, versionJson);
        callbacks.installed();

        if (session.needsAuthlibInjector() && config.useAuthlibInjector) {
            callbacks.log("Подключаем authlib-injector для Ely ...");
            AuthlibInjector.ensure(config.authlibJar(), callbacks::log);
        }

        JavaRuntimes.Runtime runtime = resolveJava(metadata, callbacks);
        Path gameDir = resolveGameDir(profile);

        CommandBuilder.Context context = new CommandBuilder.Context(
                gameDir,
                config.assetsDir(),
                metadata.assetsIndexId,
                config.versionsDir().resolve(version.id()),
                config.librariesDir(),
                config.nativesDir().resolve(version.id()),
                session,
                version.id(),
                metadata.type,
                metadata.mainClass,
                config.windowWidth,
                config.windowHeight,
                session instanceof MicrosoftSession ? MicrosoftSession.LAUNCHER_CLIENT_ID : "",
                extraJvmArgs(runtime, version.id(), profile, callbacks),
                metadata.libraries,
                GameDownloader.clientJar(metadata, config.versionsDir()));

        List<String> command = CommandBuilder.build(runtime.java(), metadata, context,
            session.needsAuthlibInjector() && config.useAuthlibInjector, config.authlibJar(),
            quickPlayAddress);

        callbacks.progress(100, "Запуск " + version.id());
        callbacks.log("Команда запуска:");
        callbacks.log(String.join(" ", command));

        // The downloader has just run, so anything still missing here is a file no download step
        // can produce — the jars the loader installer was supposed to write, above all. Starting
        // anyway would fail deep inside FML's locator; reporting it here names the files instead.
        List<Path> missing = LaunchPreflight.missing(command, config.librariesDir());
        if (!missing.isEmpty()) {
            throw new IOException(LaunchPreflight.describe(missing));
        }

        Files.createDirectories(gameDir);
        if (profile != null && profile.moddable()) {
            Files.createDirectories(loaderModsDir(version.id(), profile));
        }
        return GameProcess.start(command, gameDir, callbacks::log, code -> {
            callbacks.log(code == 0 ? "Игра закрыта" : "Игра завершилась с кодом " + code);
            callbacks.exited(code);
        });
    }

    /**
     * Loads a version JSON, expanding any {@code inheritsFrom} chain.
     *
     * <p>Mod loader profiles are version JSONs that inherit from a vanilla version, so they have to
     * be flattened before the downloader and argument builder see them. Vanilla versions carry no
     * such link and come straight from the catalog.
     */
    private JsonObject resolveVersionJson(McVersion version, ModProfile profile,
                                          Callbacks callbacks)
            throws IOException, InterruptedException {

        VersionResolver resolver = new VersionResolver(config.versionsDir());
        if (resolver.exists(version.id())) {
            if (profile != null && !resolver.exists(profile.baseVersion())) {
                McVersion base = catalog.find(profile.baseVersion());
                if (base == null) {
                    throw new IOException("Базовая версия Minecraft не найдена: "
                            + profile.baseVersion());
                }
                catalog.loadVersionJson(base, config.versionsDir());
            }
            // Already on disk, which is the modded case: the installer wrote it.
            JsonObject flat = resolver.resolve(version.id());
            if (flat.has("inheritsFrom")) {
                callbacks.log("Раскрываю профиль " + version.id()
                        + " (наследует " + flat.get("inheritsFrom").getAsString() + ")");
            }
            return flat;
        }
        return catalog.loadVersionJson(version, config.versionsDir());
    }

    /**
     * Finds a Java new enough for the version, downloading one from Adoptium when nothing local
     * qualifies.
     */
    private JavaRuntimes.Runtime resolveJava(VersionMetadata metadata, Callbacks callbacks)
            throws IOException, InterruptedException {

        int required = metadata.requiredJavaMajor();
        Path runtimesDir = config.installRoot().resolve("runtimes");
        JavaRuntimes.LauncherSettings settings = new JavaRuntimes.LauncherSettings(
                config.javaPath, runtimesDir);

        try {
            return JavaRuntimes.resolve(settings, required, callbacks::log);
        } catch (IOException noLocalJava) {
            if (!autoInstallJava) {
                throw noLocalJava;
            }
            callbacks.log("Подходящей Java " + required + " нет, загружаю с Adoptium ...");
            Path home = RuntimeInstaller.ensure(required, runtimesDir, callbacks::log);
            JavaRuntimes.Runtime installed = JavaRuntimes.detect(home.resolve("bin")
                    .resolve(System.getProperty("os.name").toLowerCase(Locale.ROOT)
                            .contains("windows") ? "java.exe" : "java"));
            if (installed == null) {
                throw new IOException("Загруженная Java " + required + " не запускается: " + home);
            }
            return installed;
        }
    }

    /**
     * Memory flags, the user's own JVM settings, and the flags a mod loader needs.
     *
     * <p>Fabric reads its mods directory from a system property. It defaults to {@code mods} inside
     * the game directory, which is shared between profiles, so it is pointed at the profile's own
     * folder to keep two Fabric setups from loading each other's mods.
     *
     * <p>Only Fabric gets that property. Forge and NeoForge have no equivalent switch and always
     * read {@code mods} from the game directory, so passing the Fabric one to them would be a
     * silently ignored argument rather than per-profile isolation.
     */
    private List<String> extraJvmArgs(JavaRuntimes.Runtime runtime, String versionId,
                                      ModProfile profile, Callbacks callbacks) {

        List<String> args = new ArrayList<>();
        int min = Math.max(0, config.minMemoryMb);
        int max = Math.max(min, config.maxMemoryMb);
        args.add("-Xms" + min + "M");
        args.add("-Xmx" + max + "M");
        if (min == 0 && max == 0) {
            // Both zero means "let the JVM pick", so no explicit flags.
            args.clear();
        }
        if (config.extraJvmArgs != null) {
            for (String part : config.extraJvmArgs.trim().split("\\s+")) {
                if (!part.isBlank()) {
                    args.add(part);
                }
            }
        }
        if (profile != null && profile.loader() == ModLoaderType.FABRIC && versionId != null) {
            args.add("-Dfabric.modsFolder=" + modsDir(versionId).toAbsolutePath());
            if (!modsDir(versionId).toFile().isDirectory()) {
                callbacks.log("Папка модов будет создана при первом запуске");
            }
        }
        return args;
    }

    /** Per-profile mods folder, for the loaders that can be pointed at one. */
    public Path modsDir(String versionId) {
        return config.versionsDir().resolve(versionId).resolve("mods");
    }

    /**
     * The folder the given profile really reads.
     *
     * <p>Fabric is told where its mods live through {@code -Dfabric.modsFolder}, so it gets a folder
     * of its own and two setups cannot load each other's jars. Forge and NeoForge have no such
     * switch: they always read {@code mods} next to the game directory, which the version JSON
     * passes as {@code --gameDir}. Handing them a folder of their own would mean creating a folder
     * the loader never looks in and dropping every mod into it, silently.
     */
    public Path loaderModsDir(String versionId, ModProfile profile) {
        if (profile != null && profile.loader() == ModLoaderType.FABRIC) {
            return modsDir(versionId);
        }
        return resolveGameDir(profile).resolve("mods");
    }

    /**
     * The instance folder for this profile, exposed for the UI: the folder shortcuts and the crash
     * report lookup need the same answer the launch uses. A null profile means plain vanilla, which
     * shares the configured game folder.
     */
    public Path gameDirFor(ModProfile profile) {
        return resolveGameDir(profile);
    }

    /**
     * One shared game folder, except that installer-backed loaders get an instance of their own,
     * so two packs cannot mix their mods. Saves used to be split per nickname under
     * {@code versions/<name>} as well, which scattered worlds across account folders; that layer
     * is gone — everything vanilla lives directly in the game folder.
     */
    private Path resolveGameDir(ModProfile profile) {
        if (config.isolateGameDir && profile != null && profile.moddable()
                && profile.loader() != ModLoaderType.FABRIC) {
            return Path.of(config.gameDir, "instances", profile.id());
        }
        return Path.of(config.gameDir);
    }
}