package com.mymods.mclauncher.p2p;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Follows {@code logs/latest.log} of a running game and reports what it learns about the world.
 *
 * <p>Polling from a remembered offset rather than a {@code WatchService}: the game renames
 * {@code latest.log} to a dated file and starts a new one on every launch, so a watch has to survive
 * the file it was given disappearing underneath it, and an offset simply resumes on the new file.
 * The log is written constantly but a few hundred milliseconds of lag on a LAN invite costs nothing.
 */
public final class WorldLogWatcher implements Closeable {

    /** What the watcher saw. Delivered on its own thread. */
    public enum Event {
        /** A singleplayer world finished starting. */
        WORLD_OPENED,
        /** The world closed, or the game exited. */
        WORLD_CLOSED,
        /** A line named the integrated server's port. */
        PORT_ANNOUNCED
    }

    /** The wording every supported version prints when the world is up. */
    private static final List<String> WORLD_OPENED_MARKERS = List.of(
            "Starting integrated minecraft server",
            "Preparing start region for dimension",
            "logged in with entity id");

    /** And the wording that means the world is gone again. */
    private static final List<String> WORLD_CLOSED_MARKERS = List.of(
            "Stopping server",
            "Stopping singleplayer server");

    private final Path logFile;
    private final Consumer<Event> onEvent;
    private final Consumer<String> onLine;
    private final long pollMillis;
    private final Thread reader;

    private volatile boolean stopped;
    private volatile boolean worldOpen;
    private long offset;
    private String partialLine = "";

    public WorldLogWatcher(Path gameDir, Consumer<Event> onEvent, Consumer<String> onLine) {
        this(gameDir.resolve("logs").resolve("latest.log"), onEvent, onLine, 400);
    }

    WorldLogWatcher(Path logFile, Consumer<Event> onEvent, Consumer<String> onLine,
                    long pollMillis) {
        this.logFile = logFile;
        this.onEvent = onEvent;
        this.onLine = onLine;
        this.pollMillis = pollMillis;
        this.reader = new Thread(this::follow, "p2p-log-watcher");
        this.reader.setDaemon(true);
    }

    public void start() {
        reader.start();
    }

    /** Whether a world has been seen open and not yet seen close. */
    public boolean isWorldOpen() {
        return worldOpen;
    }

    private void follow() {
        while (!stopped) {
            if (!readNewLines()) {
                sleep();
            }
        }
    }

    /** @return true when something was read, so a busy log does not also sleep */
    private boolean readNewLines() {
        if (!Files.isRegularFile(logFile)) {
            return false;
        }
        try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
            long length = file.length();
            if (length < offset) {
                // The game rotated the file: a new launch starts a new latest.log from nothing.
                offset = 0;
                partialLine = "";
                closeWorld();
            } else if (length == offset) {
                return false;
            }
            file.seek(offset);
            int wanted = (int) Math.min(Integer.MAX_VALUE, length - offset);
            String chunk = new String(readFully(file, wanted), StandardCharsets.UTF_8);
            offset = length;
            handle(chunk);
            return true;
        } catch (IOException e) {
            onLine.accept("Не удалось прочитать лог игры: " + e.getMessage());
            return false;
        }
    }

    /**
     * Reads exactly {@code wanted} bytes, or fewer if the file stopped growing.
     *
     * <p>{@code readAllBytes} is not an option on a random access file: it reads to the end of the
     * file, which for a log being appended to is not the end of this batch.
     */
    private static byte[] readFully(RandomAccessFile file, int wanted) throws IOException {
        byte[] buffer = new byte[wanted];
        int read = 0;
        while (read < wanted) {
            int count = file.read(buffer, read, wanted - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        return read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);
    }

    private void handle(String chunk) {
        String text = partialLine + chunk;
        int newline = text.lastIndexOf('\n');
        if (newline < 0) {
            partialLine = text;
            return;
        }
        partialLine = text.substring(newline + 1);
        for (String line : text.substring(0, newline).split("\r?\n")) {
            inspect(line);
        }
    }

    private void inspect(String line) {
        for (String marker : WORLD_OPENED_MARKERS) {
            if (line.contains(marker)) {
                openWorld();
                break;
            }
        }
        for (String marker : WORLD_CLOSED_MARKERS) {
            if (line.contains(marker)) {
                closeWorld();
                break;
            }
        }
        if (LogPortScanner.announcesPort(line)) {
            onEvent.accept(Event.PORT_ANNOUNCED);
        }
    }

    private void openWorld() {
        if (!worldOpen) {
            worldOpen = true;
            onEvent.accept(Event.WORLD_OPENED);
        }
    }

    private void closeWorld() {
        if (worldOpen) {
            worldOpen = false;
            onEvent.accept(Event.WORLD_CLOSED);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(pollMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped = true;
        }
    }

    @Override
    public void close() {
        stopped = true;
        reader.interrupt();
    }
}
