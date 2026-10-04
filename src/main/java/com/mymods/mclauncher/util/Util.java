package com.mymods.mclauncher.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Consumer;

/** Small helpers for filesystem checks, logging and progress reporting. */
public final class Util {

    private Util() {}

    public static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[16384];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable", e);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Runs a task off the UI thread and swallows nothing, reporting failures through {@code onError}. */
    public static void background(String label, ThrowingRunnable task, Consumer<Exception> onError) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (Exception e) {
                onError.accept(e);
            }
        }, label);
        thread.setDaemon(true);
        thread.start();
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}