package com.mymods.mclauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mymods.mclauncher.auth.OfflineSession;
import com.mymods.mclauncher.config.LauncherConfig;
import com.mymods.mclauncher.mc.GameProcess;
import com.mymods.mclauncher.mc.JavaRuntimes;
import com.mymods.mclauncher.mc.McVersion;
import com.mymods.mclauncher.mc.ModLoaderCatalog;
import com.mymods.mclauncher.mc.ModLoaderInstaller;
import com.mymods.mclauncher.mc.ModLoaderType;
import com.mymods.mclauncher.mc.ModProfile;
import com.mymods.mclauncher.mc.ModProfileStore;
import com.mymods.mclauncher.mc.VersionCatalog;

/**
 * Actually starts Minecraft and watches its log, so the launch pipeline can be verified without
 * clicking through the UI.
 *
 * <p>Run with {@code gradlew smokeLaunch "-PmcVersion=1.20.1"}. Add {@code "-Ploader=fabric"} to
 * install a Fabric profile and start that instead. The game is stopped as soon as the window comes
 * up or {@code -PtimeoutSeconds} elapses.
 */
public final class SmokeLaunch {

    /**
     * Lines proving the client is past argument parsing, used for the generic verdict.
     *
     * <p>These are all vanilla lines, so on their own they cannot tell a working Fabric launch from
     * a vanilla one that quietly ignored the loader. {@link #requiredMarker} is what distinguishes
     * the two.
     */
    private static final List<String> STARTUP_MARKERS = List.of(
            "Setting user:",
            "Backend library:",
            "Created: ");

    /**
     * The one line that proves the thing actually under test came up.
     *
     * <p>Vanilla never prints the loader's own line, so requiring it is what stops a broken profile
     * from reporting a pass just because the game window opened. Each loader announces itself
     * differently: Fabric names itself while loading Minecraft, and FML-based loaders hand over to
     * ModLauncher.
     */
    private static String requiredMarker(String loaderId) {
        return switch (ModLoaderType.parse(loaderId)) {
            case FABRIC -> "Loading Minecraft ";
            case FORGE, NEOFORGE -> "ModLauncher running";
            case VANILLA -> "Setting user:";
        };
    }

    public static void main(String[] args) throws Exception {
        String versionId = args.length > 0 ? args[0] : "1.20.1";
        String loaderId = System.getProperty("loader", "");
        long timeout = Long.parseLong(System.getProperty("timeoutSeconds", "180"));

        // Defaults to a scratch directory so a run never touches the user's real install, but
        // accepts an override so a failing profile can be reproduced where it actually failed.
        Path gameDir = Path.of(System.getProperty("gameDir",
                Path.of(System.getProperty("user.home"), "MCLauncherHeadlessCheck").toString()));
        LauncherConfig config = LauncherConfig.load(gameDir);
        config.gameDir = gameDir.toString();

        VersionCatalog catalog = new VersionCatalog();
        catalog.refresh();
        McVersion base = catalog.find(versionId);
        if (base == null) {
            throw new IllegalArgumentException("No such version: " + versionId);
        }

        // A mod loader profile is reached through a synthetic McVersion carrying the profile id:
        // the JSON is already on disk, so the catalog entry is never consulted for it.
        McVersion version = base;
        ModProfile profile = null;
        if (!loaderId.isBlank()) {
            ModLoaderType loader = ModLoaderType.parse(loaderId);
            String requested = System.getProperty("loaderVersion", "");
            String loaderVersion = requested.isBlank()
                    ? ModLoaderCatalog.latestBuild(loader, versionId)
                    : requested;
            profile = new ModProfile(
                    ModProfile.buildId(loader, versionId, loaderVersion),
                    versionId, loader, loaderVersion, List.of(), null);
            System.out.println("Installing profile " + profile.id());
            String installedId;
            // If the profile is already on disk, reuse it rather than installing it again: the
            // installer takes minutes and would just download everything a second time.
            ModProfileStore onDisk = new ModProfileStore(config.versionsDir());
            ModProfile already = onDisk.find(profile.id());
            if (already != null) {
                System.out.println("Reusing installed profile " + already.id());
                installedId = already.id();
            } else {
                installedId = new ModLoaderInstaller(config.installRoot(),
                        JavaRuntimes.resolve(new JavaRuntimes.LauncherSettings(config.javaPath,
                                config.installRoot().resolve("runtimes")), 17, System.out::println)
                                .java(),
                        System.out::println).install(profile);
                // Installers do not all name the version after our own id scheme, so launch
                // whatever they actually wrote rather than what we predicted.
                ModProfile written = onDisk.find(installedId);
                if (written != null) {
                    profile = written;
                }
            }
            version = new McVersion(installedId, "release", "", "");
        }

        List<String> log = new CopyOnWriteArrayList<>();
        GameStarter starter = new GameStarter(config, catalog);
        GameProcess process = starter.launch(version, profile, new OfflineSession("SmokeTester"),
                new GameStarter.Callbacks() {
                    @Override
                    public void log(String line) {
                        System.out.println(line);
                        log.add(line);
                    }

                    @Override
                    public void progress(int percent, String what) {
                        if (percent % 20 == 0) {
                            System.out.println(percent + "% — " + what);
                        }
                    }

                    @Override
                    public void failed(Exception e) {
                        e.printStackTrace();
                    }
                });

        System.out.println("Started PID " + process.pid() + ", waiting up to " + timeout + "s");

        String required = requiredMarker(loaderId);
        System.out.println("Waiting for: \"" + required + "\"");

        long deadline = System.currentTimeMillis() + timeout * 1000L;
        boolean started = false;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
            if (!process.isAlive()) {
                break;
            }
            for (String line : log) {
                if (line.contains(required)) {
                    started = true;
                }
            }
            if (started) {
                break;
            }
        }

        dumpLog(log);

        // Always stop the game. Leaving it running on a pass would hold the native DLLs open and
        // make the next run fail while unpacking them, which looks like a launcher bug.
        if (process.isAlive()) {
            process.stop();
        }

        System.out.println("\n=== RESULT ===");
        if (started) {
            System.out.println("PASS: " + (loaderId.isBlank()
                    ? "vanilla reached the loading stage"
                    : loaderId + " reached the loading stage"));
        } else {
            // The generic markers are printed so a failed run still shows how far it got.
            for (String line : log) {
                for (String marker : STARTUP_MARKERS) {
                    if (line.contains(marker)) {
                        System.out.println("  saw generic marker: " + line);
                        break;
                    }
                }
            }
            System.out.println("FAIL: \"" + required + "\" never appeared within " + timeout + "s");
        }

        writeReport(gameDir.resolve("smoke-launch.log"), log, started);
        if (!started) {
            System.exit(1);
        }
    }

    private static void dumpLog(List<String> log) {
        int from = Math.max(0, log.size() - 60);
        System.out.println("--- last log lines ---");
        for (String line : log.subList(from, log.size())) {
            System.out.println(line);
        }
    }

    private static void writeReport(Path file, List<String> log, boolean passed) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(passed ? "PASS" : "FAIL").append('\n');
        log.forEach(line -> sb.append(line).append('\n'));
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("Report written to " + file);
    }
}
