package com.mymods.mclauncher.mc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs Minecraft as a child process and mirrors its output to the UI log.
 *
 * <p>Log lines arrive on a background reader thread and are handed to a consumer; the UI is
 * responsible for hopping back to the JavaFX thread.
 */
public final class GameProcess {

    private final Process process;
    private final Thread reader;

    public GameProcess(Process process, Consumer<String> onLine, Consumer<Integer> onExit) {
        this.process = process;
        this.reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    onLine.accept(stripMinecraftPrefix(line));
                }
            } catch (IOException e) {
                onLine.accept("[процесс] поток вывода закрыт: " + e.getMessage());
            }
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                code = -1;
            }
            onExit.accept(code);
        }, "minecraft-stdout");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    /** Vanilla prefixes console lines with [main/INFO]; keep them but trim the colour codes. */
    private static String stripMinecraftPrefix(String line) {
        return line.replaceAll("§.", "");
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Stops the game, escalating if it will not go quietly.
     *
     * <p>A gentle terminate is tried first so Minecraft can flush its world, but its shutdown can
     * hang on a stuck GL context. The forceful kill is then given its own wait, because
     * {@code destroyForcibly} only signals and returns: without it the process is still running
     * when this returns, and the natives it holds stay locked.
     */
    public void stop() {
        process.destroy();
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    public long pid() {
        return process.pid();
    }

    public static GameProcess start(List<String> command, Path workingDir, Consumer<String> onLine,
                                     Consumer<Integer> onExit) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(workingDir.toFile());
        builder.redirectErrorStream(true);
        return new GameProcess(builder.start(), onLine, onExit);
    }
}