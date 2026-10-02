package com.mymods.mclauncher.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Writes the launcher's own log lines to a file next to the settings.
 *
 * <p>The window shows only the last few hundred lines and forgets them when it closes, so a failure
 * that happened while the window was shut could not be looked at afterwards. Since every install and
 * launch step reports through this, a failed run is reproducible from the file alone.
 *
 * <p>Rotated at the start of every run: keeping the previous attempt matters more than keeping the
 * current one, and an unbounded file would grow without limit.
 */
public final class FileLog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private FileLog() {
    }

    /**
     * Appends to {@code launcher.log} inside the data directory, rotating it first if it is large.
     *
     * @return a consumer safe to call from any thread, or a no-op if the file cannot be opened
     */
    public static Consumer<String> open(Path dataDir) {
        Path file = dataDir.resolve("launcher.log");
        try {
            Files.createDirectories(dataDir);
            rotate(file);
            BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return queuedWriter(writer);
        } catch (IOException e) {
            // A launcher that cannot write its log must still start, so say so once and carry on.
            System.err.println("Не удалось открыть " + file + ": " + e.getMessage());
            return line -> {
            };
        }
    }

    private static Consumer<String> queuedWriter(BufferedWriter writer) {
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        Thread worker = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String first = queue.poll(250, TimeUnit.MILLISECONDS);
                    if (first == null) {
                        writer.flush();
                        continue;
                    }
                    writeLine(writer, first);
                    for (int count = 1; count < 256; count++) {
                        String next = queue.poll();
                        if (next == null) {
                            break;
                        }
                        writeLine(writer, next);
                    }
                    writer.flush();
                }
            } catch (InterruptedException e) {
                try {
                    drain(writer, queue);
                } catch (IOException ignored) {
                }
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
            } finally {
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }, "launcher-file-log");
        worker.setDaemon(true);
        worker.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            worker.interrupt();
            try {
                worker.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "launcher-file-log-shutdown"));
        return line -> queue.offer("[" + LocalDateTime.now().format(STAMP) + "] " + line);
    }

    private static void drain(BufferedWriter writer, LinkedBlockingQueue<String> queue)
            throws IOException {
        String line;
        while ((line = queue.poll()) != null) {
            writeLine(writer, line);
        }
    }

    private static void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
    }

    private static void rotate(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) < MAX_BYTES) {
            return;
        }
        Path previous = file.resolveSibling("launcher-previous.log");
        Files.deleteIfExists(previous);
        Files.move(file, previous);
    }
}