package com.mymods.mclauncher.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Streams a URL to disk while reporting bytes read, used for download progress. */
public final class DownloadStream {

    /** Receives the number of bytes read so far for each chunk. */
    @FunctionalInterface
    public interface Listener {
        void onBytes(long total);
    }

    private DownloadStream() {}

    public static void toFile(String url, Path target, Listener listener)
            throws IOException, InterruptedException {

        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.createDirectories(target.getParent());

        try (InputStream in = Http.openStream(url)) {
            byte[] buffer = new byte[65536];
            long total = 0;
            try (var out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    total += read;
                    if (listener != null) {
                        listener.onBytes(total);
                    }
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}