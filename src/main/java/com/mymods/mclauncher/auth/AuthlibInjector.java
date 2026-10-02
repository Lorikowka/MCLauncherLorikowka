package com.mymods.mclauncher.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.mymods.mclauncher.util.Http;

/**
 * Downloads the authlib-injector jar used as a {@code -javaagent} for Ely sessions.
 *
 * <p>GitHub's API is not reachable from every network, so the launcher resolves the latest
 * release through the public releases page and falls back to a pinned known-good build.
 */
public final class AuthlibInjector {

    /** Upstream project. Ely's docs link to it as the recommended java agent. */
    private static final String GITHUB_RELEASES =
            "https://api.github.com/repos/yushijinhun/authlib-injector/releases/latest";

    /** Used when the GitHub API is unreachable; verified against release v1.2.8. */
    private static final String FALLBACK_URL =
            "https://github.com/yushijinhun/authlib-injector/releases/download/v1.2.8/authlib-injector-1.2.8.jar";

    private AuthlibInjector() {}

    /**
     * Ensures the injector exists at {@code target}, downloading it when missing or corrupt.
     *
     * @param onProgress receives status lines
     */
    public static Path ensure(Path target, Consumer<String> onProgress)
            throws IOException, InterruptedException {

        if (Files.isRegularFile(target) && isValidJar(target)) {
            return target;
        }

        String url = FALLBACK_URL;
        try {
            String resolved = resolveFrom(GITHUB_RELEASES);
            if (resolved != null) {
                url = resolved;
            }
        } catch (Exception e) {
            onProgress.accept("GitHub API недоступен, беру закреплённую сборку v1.2.8");
        }

        onProgress.accept("Загрузка authlib-injector ...");
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = Http.openStream(url)) {
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (!isValidJar(tmp)) {
            Files.deleteIfExists(tmp);
            throw new IOException("Загруженный authlib-injector повреждён: " + url);
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** Picks the jar asset out of a GitHub release payload. */
    private static String resolveFrom(String source) throws IOException, InterruptedException {
        var release = Http.getJson(source);
        if (!release.has("assets")) {
            return null;
        }
        for (var asset : release.getAsJsonArray("assets")) {
            String name = asset.getAsJsonObject().get("name").getAsString();
            if (name.endsWith(".jar")) {
                return asset.getAsJsonObject().get("browser_download_url").getAsString();
            }
        }
        return null;
    }

    private static boolean isValidJar(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.getEntry("META-INF/MANIFEST.MF") != null;
        } catch (IOException e) {
            return false;
        }
    }
}