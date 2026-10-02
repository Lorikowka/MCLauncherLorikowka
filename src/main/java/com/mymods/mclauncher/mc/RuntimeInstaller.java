package com.mymods.mclauncher.mc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.mymods.mclauncher.util.Http;

/**
 * Downloads a JRE from Adoptium so the launcher can run Minecraft versions whose Java
 * requirements differ from the machine's default.
 */
public final class RuntimeInstaller {

    private static final String API = "https://api.adoptium.net/v3/assets/latest";

    private RuntimeInstaller() {}

    /**
     * Ensures a managed runtime for {@code major} exists under {@code runtimesDir}.
     *
     * @return the installed runtime directory
     */
    public static Path ensure(int major, Path runtimesDir, java.util.function.Consumer<String> log)
            throws IOException, InterruptedException {

        Path target = runtimesDir.resolve("jdk-" + major);
        if (isInstalled(target)) {
            log.accept("Java " + major + " уже загружена");
            return target;
        }

        Files.createDirectories(runtimesDir);
        String url = resolveUrl(major);
        log.accept("Загрузка Java " + major + " (" + Math.round(fileSize(url) / 1_048_576d) + " МБ) ...");

        Path archive = runtimesDir.resolve("jdk-" + major + downloadExtension(url));
        if (!Files.isRegularFile(archive)) {
            Http.downloadToFile(url, archive);
        }
        extract(archive, target);
        Files.deleteIfExists(archive);

        if (!isInstalled(target)) {
            throw new IOException("Java " + major + " распаковалась некорректно: " + target);
        }
        log.accept("Java " + major + " установлена");
        return target;
    }

    private static String downloadExtension(String url) {
        return url.contains(".zip") ? ".zip" : url.contains(".tar.gz") ? ".tar.gz" : ".zip";
    }

    /** Asks Adoptium for the newest build of the given major version on this platform. */
    public static String resolveUrl(int major) throws IOException, InterruptedException {
        String os = Library.currentOs().equals("windows") ? "windows"
                : Library.currentOs().equals("osx") ? "mac" : "linux";
        String arch = switch (Library.currentArch()) {
            case "x86_64" -> "x64";
            case "x86" -> "x86";
            default -> "aarch64";
        };
        String url = String.format("%s/%d/hotspot?architecture=%s&image_type=jre&os=%s&vendor=eclipse",
                API, major, arch, os);
        // The endpoint answers with an array of releases, newest first.
        var releases = Http.getJsonArray(url);
        if (releases.isEmpty()) {
            throw new IOException("Adoptium не нашёл сборку Java " + major + " для " + os + "/" + arch);
        }
        var binary = releases.get(0).getAsJsonObject().getAsJsonObject("binary");
        if (binary == null || !binary.has("package")) {
            throw new IOException("У сборки Java " + major + " нет файла пакета");
        }
        return binary.getAsJsonObject("package").get("link").getAsString();
    }

    private static long fileSize(String url) throws IOException, InterruptedException {
        java.net.http.HttpRequest request = Http.request(url)
                .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();
        var response = Http.client().send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
        return response.headers().firstValue("content-length").map(Long::parseLong).orElse(0L);
    }

    private static boolean isInstalled(Path home) {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Path java = home.resolve("bin").resolve(osName.contains("windows") ? "java.exe" : "java");
        return Files.isRegularFile(java);
    }

    /**
     * Unpacks the archive into {@code target}.
     *
     * <p>Adoptium wraps every build in a single top-level directory such as
     * {@code jdk-17.0.11+9-jre}. That folder is stripped so the result always looks like
     * {@code target/bin/java} regardless of the build number.
     */
    private static void extract(Path archive, Path target) throws IOException {
        Path staging = target.resolveSibling(target.getFileName() + "-unpack");
        deleteTree(staging);
        Files.createDirectories(staging);
        try {
            String name = archive.getFileName().toString();
            if (name.endsWith(".zip")) {
                unzip(archive, staging);
            } else {
                untarGz(archive, staging);
            }

            Path root = singleRoot(staging);
            deleteTree(target);
            Files.createDirectories(target.getParent());
            if (root.equals(staging)) {
                // Some archives have no wrapper directory, so unpack in place.
                unzip(archive, target);
            } else {
                Files.move(root, target);
            }
        } finally {
            deleteTree(staging);
        }
    }

    /**
     * Returns the only entry of {@code dir} when the archive had a wrapper directory, otherwise
     * {@code dir} itself.
     */
    private static Path singleRoot(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            var list = entries.toList();
            if (list.size() == 1 && Files.isDirectory(list.get(0))) {
                return list.get(0);
            }
        }
        return dir;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path entry : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static void unzip(Path archive, Path target) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path out = target.resolve(entry.getName()).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("Некорректный путь в архиве: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** macOS refuses to execute unsigned tarball binaries, so tar is invoked through sh. */
    private static void untarGz(Path archive, Path target) throws IOException {
        Process process = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", target.toString())
                .redirectErrorStream(true)
                .start();
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("Распаковка tar прервалась по таймауту");
            }
            if (process.exitValue() != 0) {
                throw new IOException("tar завершился с кодом " + process.exitValue());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Распаковка прервана", e);
        }
    }
}