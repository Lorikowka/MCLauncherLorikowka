package com.mymods.mclauncher.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Manages which versions are installed on disk.
 *
 * <p>Versions are independent directories under the versions root, so removing one is a directory
 * delete. Shared data (libraries, assets) is deliberately left alone: a different version usually
 * needs it, and re-downloading it is far slower than keeping it.
 */
public final class VersionStore {

    private final Path versionsDir;
    private final Path assetsDir;
    private final ModProfileStore profiles;

    public VersionStore(Path versionsDir, Path assetsDir) {
        this.versionsDir = versionsDir;
        this.assetsDir = assetsDir;
        this.profiles = new ModProfileStore(versionsDir);
    }

    /** Installed version ids, newest first by last-modified time. */
    public List<String> installed() {
        if (!Files.isDirectory(versionsDir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(versionsDir)) {
            List<Path> dirs = children.filter(Files::isDirectory)
                    .filter(this::isInstalledDir)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            dirs.sort(Comparator.comparingLong(this::lastModified).reversed());
            List<String> ids = new ArrayList<>(dirs.size());
            for (Path dir : dirs) {
                ids.add(dir.getFileName().toString());
            }
            return ids;
        } catch (IOException e) {
            return List.of();
        }
    }

    public boolean isInstalled(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return false;
        }
        return isInstalledDir(versionsDir.resolve(versionId));
    }

    /**
     * Whether a version directory counts as installed.
     *
     * <p>A vanilla version is installed once its client jar is there, since that is the last file
     * its install writes. A mod loader profile never has a jar of its own: it inherits the vanilla
     * one and adds libraries, so its version JSON is the marker instead.
     */
    private boolean isInstalledDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        String id = dir.getFileName().toString();
        return Files.isRegularFile(dir.resolve(id + ".jar")) || profiles.isProfile(id);
    }

    /**
     * Deletes one installed version.
     *
     * <p>The jar inside a version directory is what {@link #isInstalled} looks for, so an
     * interrupted download that only left a {@code .part} file is not reported and not counted.
     */
    public boolean delete(String versionId) throws IOException {
        if (!isInstalled(versionId)) {
            return false;
        }
        deleteRecursively(versionsDir.resolve(versionId));
        return true;
    }

    /** Deletes every installed version. Returns how many were removed. */
    public int deleteAll() throws IOException {
        List<String> ids = installed();
        int removed = 0;
        for (String id : ids) {
            if (delete(id)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Deletes asset indexes and objects.
     *
     * <p>Separate from the version delete because assets are shared, and this is the only way to
     * reclaim the several gigabytes they take.
     */
    public boolean deleteAssets() throws IOException {
        if (!Files.isDirectory(assetsDir)) {
            return false;
        }
        deleteRecursively(assetsDir);
        return true;
    }

    /** Total size in bytes of an installed version, for showing what a delete will free. */
    public long sizeOf(String versionId) {
        return sizeRecursively(versionsDir.resolve(versionId));
    }

    public long assetsSize() {
        return sizeRecursively(assetsDir);
    }

    private long lastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Recursive delete that resolves symlinks inside the tree rather than following them, so a
     * stray link cannot delete files outside the launcher directory.
     */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static long sizeRecursively(Path root) {
        if (!Files.isDirectory(root)) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Renders a byte count the way a settings screen shows it. */
    public static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "0 МБ";
        }
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1024) {
            return mb < 10 ? String.format(Locale.ROOT, "%.1f МБ", mb)
                    : Math.round(mb) + " МБ";
        }
        return String.format(Locale.ROOT, "%.1f ГБ", mb / 1024.0);
    }
}
