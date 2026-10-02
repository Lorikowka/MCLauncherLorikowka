package com.mymods.mclauncher.mc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a mod loader's installer jar and keeps what it produced.
 *
 * <p>Forge and NeoForge both ship a Gradle-built installer that has to be executed with a JVM; it
 * downloads the loader's libraries, patches the client and writes a version JSON. The installer
 * expects to be pointed at a game root, so it is run against the launcher's own install root, which
 * already has the {@code versions} and {@code libraries} layout it writes into.
 *
 * <p>Two behaviours of the installers are worth stating because they are easy to trip over:
 * they refuse to run unless a {@code launcher_profiles.json} is present, and they report failure
 * in their log text rather than only through the exit code.
 */
public final class InstallerJarRunner {

    /** How long an installer may run before it is treated as stuck. */
    private static final long DEFAULT_TIMEOUT_MINUTES = 30;

    private final Path installRoot;
    private final Path javaExecutable;
    private final Consumer<String> log;

    public InstallerJarRunner(Path installRoot, Path javaExecutable, Consumer<String> log) {
        this.installRoot = installRoot;
        this.javaExecutable = javaExecutable;
        this.log = log;
    }

    /**
     * Downloads the installer and runs it.
     *
     * @param installerUrl where the installer jar is published
     * @param profileHint  id used to name the cached installer jar and the log lines
     * @return the exit code of the installer
     */
    public int run(String installerUrl, String profileHint) throws IOException,
            InterruptedException {

        Path jar = installRoot.resolve("installers")
                .resolve(profileHint + "-installer.jar");
        downloadInstaller(installerUrl, jar);
        seedLauncherProfiles();

        log.accept(loaderLabel(profileHint) + ": запускаю установщик, это занимает несколько минут");
        int exit = execute(jar, profileHint);

        // Whether the installer produced a usable profile is deliberately not checked here. The
        // version it writes is named by the loader, not by us: Forge writes "1.20.1-forge-47.4.23"
        // while NeoForge writes "neoforge-20.4.251", so guessing the path here would reject
        // successful installs. ModLoaderInstaller looks at what actually landed instead.
        if (exit != 0) {
            throw new IOException(loaderLabel(profileHint)
                    + ": установщик завершился с кодом " + exit);
        }
        return exit;
    }

    /** Fetches the installer jar, keeping an already downloaded copy. */
    private void downloadInstaller(String url, Path target) throws IOException,
            InterruptedException {

        if (Files.isRegularFile(target)) {
            log.accept("Установщик уже скачан: " + target.getFileName());
            return;
        }
        log.accept("Скачиваю установщик: " + url);
        com.mymods.mclauncher.util.Http.downloadToFile(url, target);
        log.accept("Установщик сохранён: " + target.getFileName());
    }

    /**
     * Writes the launcher profile file the installer insists on.
     *
     * <p>Both installers abort with "There is no Minecraft launcher profile" when this file is
     * missing, even for a headless {@code --installClient} run.
     */
    private void seedLauncherProfiles() throws IOException {
        Path file = installRoot.resolve("launcher_profiles.json");
        if (Files.isRegularFile(file)) {
            return;
        }
        Files.createDirectories(installRoot);
        Files.writeString(file, """
                {
                  "profiles": {},
                  "selectedProfile": "",
                  "clientVersion": "",
                  "authenticationDatabase": {},
                  "launcherVersion": { "name": "MCLauncher", "format": 24 }
                }
                """, StandardCharsets.UTF_8);
        log.accept("Создан launcher_profiles.json для установщика");
    }

    /**
     * Runs {@code --installClient} and streams its output to the launcher log.
     *
     * <p>The output is forwarded live because an install that stalls is otherwise indistinguishable
     * from one that is working, and the timeout below is deliberately generous: the first run
     * downloads hundreds of megabytes of libraries.
     *
     * <p>The output is drained on its own thread. Reading it inline would block in {@code readLine}
     * until the installer closed the stream, so the timeout check below would only ever run after a
     * successful exit and could never fire.
     */
    private int execute(Path jar, String profileHint) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                javaExecutable.toAbsolutePath().toString(),
                // The installer can pop a dialog when something is missing; headless makes that a
                // hard failure in the log instead of an invisible window nobody can dismiss.
                "-Djava.awt.headless=true",
                "-Xmx2G",
                "-jar",
                jar.toAbsolutePath().toString(),
                "--installClient",
                installRoot.toAbsolutePath().toString()));

        log.accept("Команда установщика:");
        log.accept(String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(installRoot.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder tail = new StringBuilder();
        Thread pump = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    log.accept(loaderLabel(profileHint) + ": " + line);
                    // Kept so a failure can be reported with the lines that explain it, since a long
                    // install buries the reason under hundreds of progress lines.
                    tail.append(line).append('\n');
                    if (tail.length() > 8000) {
                        tail.delete(0, tail.length() - 4000);
                    }
                }
            } catch (IOException closed) {
                // The stream closes when the process is killed on timeout; nothing to report.
            }
        }, "installer-output");
        pump.setDaemon(true);
        pump.start();

        if (!process.waitFor(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            pump.join(TimeUnit.SECONDS.toMillis(5));
            throw new IOException(loaderLabel(profileHint) + ": установщик не ответил за "
                    + DEFAULT_TIMEOUT_MINUTES + " минут");
        }
        // Joining after the exit establishes a happens-before, so the tail is safe to read here.
        pump.join(TimeUnit.SECONDS.toMillis(5));

        int exit = process.exitValue();
        if (exit != 0) {
            log.accept("Последние строки установщика:\n" + tail);
        }
        return exit;
    }

    private static String loaderLabel(String versionId) {
        if (versionId.contains("neoforge")) {
            return "NeoForge";
        }
        if (versionId.contains("forge")) {
            return "Forge";
        }
        return "Установщик";
    }
}
