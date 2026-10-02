package com.mymods.mclauncher.mc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Unpacks native shared libraries from the {@code -natives} jars Mojang ships.
 *
 * <p>Only platform-relevant binaries are extracted, and every entry is checked against
 * zip-slip before being written outside the target directory.
 */
public final class NativeExtractor {

    private NativeExtractor() {}

    public static List<String> extract(Path jar, Path targetDir) throws IOException {
        List<String> written = new ArrayList<>();
        Files.createDirectories(targetDir);

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.contains("/META-INF/") || name.startsWith("META-INF/")) {
                    continue;
                }
                if (!isRelevant(name)) {
                    continue;
                }
                Path out = targetDir.resolve(name).normalize();
                if (!out.startsWith(targetDir)) {
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = jarFile.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                written.add(name);
            }
        }
        return written;
    }

    /** Keeps native binaries for the running platform and drops source/javadoc artifacts. */
    private static boolean isRelevant(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java") || lower.endsWith(".so.1") && !lower.contains("arm") && !lower.contains("x86")) {
            return false;
        }
        if (lower.endsWith(".dll") || lower.endsWith(".dylib") || lower.endsWith(".so")
                || lower.contains(".so.")) {
            return matchesArch(lower);
        }
        return false;
    }

    private static boolean matchesArch(String lower) {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm");
        boolean hasArm = lower.contains("arm");
        boolean hasX86 = lower.contains("x86");
        if (hasArm && hasX86) {
            // Universal binaries (e.g. libfreetype.dylib) are fine on either arch.
            return true;
        }
        return arm == hasArm;
    }
}