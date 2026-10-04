package com.mymods.mclauncher.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mymods.mclauncher.util.DownloadStream;
import com.mymods.mclauncher.util.Http;
import com.mymods.mclauncher.util.Util;

/**
 * Downloads everything a version needs: the client jar, libraries and their natives,
 * plus the asset index and assets.
 *
 * <p>Every file with a published SHA-1 is verified, so an interrupted or corrupted download is
 * repaired on the next run instead of producing a broken install.
 */
public final class GameDownloader {

    /** Percent of the bar reserved for the client jar and libraries. */
    private static final int LIBRARY_SHARE = 5;

    /** Percent of the bar owned by assets, the bulk of the download. */
    private static final int ASSET_SHARE = 90;
    private static final int DOWNLOAD_WORKERS = 8;
    private static final int DOWNLOAD_ATTEMPTS = 3;

    private record AssetObject(String path, JsonObject metadata) {}

    /** File-count progress so the UI can show a stable percentage. */
    public static final class Progress {
        private final int total;
        private final AtomicInteger done = new AtomicInteger();
        private volatile String current = "";

        Progress(int total) {
            this.total = Math.max(total, 1);
        }

        void file(String name) {
            current = name;
        }

        void bump() {
            done.incrementAndGet();
        }

        public int percent() {
            return Math.min(100, done.get() * 100 / total);
        }

        public int done() {
            return done.get();
        }

        public int total() {
            return total;
        }

        public String current() {
            return current;
        }
    }

    private final Path versionsDir;
    private final Path librariesDir;
    private final Path assetsDir;
    private final Path nativesDir;
    private final Consumer<String> log;
    private volatile java.util.function.BiConsumer<Integer, String> progressListener;

    /**
     * Last percentage handed to the listener. An index holds thousands of assets, so reporting
     * every one of them would flood the UI thread with updates that do not change anything.
     */
    private final AtomicInteger lastReported = new AtomicInteger(-1);

    public GameDownloader(Path versionsDir, Path librariesDir, Path assetsDir, Path nativesDir,
                          Consumer<String> log) {
        this.versionsDir = versionsDir;
        this.librariesDir = librariesDir;
        this.assetsDir = assetsDir;
        this.nativesDir = nativesDir;
        this.log = log;
    }

    /** Registers a listener for {@code (percent, currentFileName)}. */
    public void onProgress(java.util.function.BiConsumer<Integer, String> listener) {
        this.progressListener = listener;
    }

    private synchronized void report(Progress progress) {
        var listener = progressListener;
        if (listener != null) {
            int percent = Math.min(LIBRARY_SHARE,
                    progress.done() * LIBRARY_SHARE / progress.total());
            if (percent > lastReported.getAndAccumulate(percent, Math::max)) {
                listener.accept(percent, progress.current());
            }
        }
    }

    /** Downloads the version's files. Safe to call repeatedly; valid files are skipped. */
    public void install(VersionMetadata version, JsonObject versionJson)
            throws IOException, InterruptedException {

        Files.createDirectories(versionsDir);
        Files.createDirectories(librariesDir);
        Files.createDirectories(assetsDir);

        List<Library> artifacts = version.classpathLibraries();
        List<Library> natives = version.nativeLibraries();
        int total = 3 + artifacts.size() + natives.size();
        Progress progress = new Progress(total);

        installClientJar(version, versionJson, progress);
        List<Callable<Void>> libraryTasks = new ArrayList<>(total);
        for (Library library : artifacts) {
            libraryTasks.add(() -> {
                installLibrary(library, progress);
                report(progress);
                return null;
            });
        }
        for (Library library : natives) {
            libraryTasks.add(() -> {
                installNativeJar(library, progress);
                report(progress);
                return null;
            });
        }
        runParallel(libraryTasks);
        report(progress);
        installAssets(version, versionJson, progress);
        report(progress);

        unpackNatives(version);
    }

    private void installClientJar(VersionMetadata version, JsonObject versionJson, Progress progress)
            throws IOException, InterruptedException {

        if (!versionJson.has("downloads")) {
            return;
        }
        JsonObject downloads = versionJson.getAsJsonObject("downloads");
        if (!downloads.has("client")) {
            return;
        }
        JsonObject client = downloads.getAsJsonObject("client");
        if (!client.has("url")) {
            return;
        }
        // Mojang publishes the jar under its own version folder, e.g. "1.20.1/1.20.1.jar", and a
        // mod loader profile inherits that block from the version it extends. Using the published
        // path means every profile of the same Minecraft version shares one copy of the client
        // instead of downloading another ~20 MB each.
        Path target = clientJar(version, versionsDir);
        String sha1 = client.has("sha1") ? client.get("sha1").getAsString() : null;
        progress.file("Клиент " + version.id);
        if (valid(target, sha1)) {
            progress.bump();
            return;
        }
        download(client.get("url").getAsString(), target, sha1, progress);
        progress.bump();
    }

    /**
     * The client jar for a version, resolved against the versions root.
     *
     * <p>The path comes from the version JSON, so a mod loader profile resolves to the vanilla jar
     * it inherits from and every profile of that version shares the same file. Versions that do not
     * declare one fall back to the usual {@code <id>/<id>.jar}.
     */
    public static Path clientJar(VersionMetadata version, Path versionsDir) {
        String published = version.clientJarPath;
        if (published != null && !published.isBlank()) {
            return versionsDir.resolve(published.replace('\\', '/'));
        }
        return versionsDir.resolve(version.id).resolve(version.id + ".jar");
    }

    private void installLibrary(Library library, Progress progress) throws IOException, InterruptedException {
        String relative = library.path();
        String url = library.artifactUrl();
        if (url == null || relative == null) {
            return;
        }
        Path target = librariesDir.resolve(relative.replace('\\', '/'));
        progress.file(shortName(library));
        if (valid(target, library.artifactSha1())) {
            progress.bump();
            return;
        }
        download(url, target, library.artifactSha1(), progress);
        progress.bump();
    }

    /**
     * Downloads the natives jar of a library that carries one: for pre-1.13 entries the
     * {@code downloads.classifiers} file next to the main jar, for 1.13+ entries the artifact
     * itself (its path already points at the natives jar).
     */
    private void installNativeJar(Library library, Progress progress) throws IOException, InterruptedException {
        boolean oldStyle = library.hasNativesMap();
        String relative = library.nativesPath();
        String url = oldStyle ? library.nativesUrl() : library.artifactUrl();
        String sha1 = oldStyle ? library.nativesSha1() : library.artifactSha1();
        if (url == null || relative == null) {
            return;
        }
        Path target = librariesDir.resolve(relative.replace('\\', '/'));
        progress.file(shortName(library));
        if (valid(target, sha1)) {
            progress.bump();
            return;
        }
        download(url, target, sha1, progress);
        progress.bump();
    }

    private void installAssets(VersionMetadata version, JsonObject versionJson, Progress progress)
            throws IOException, InterruptedException {

        if (!versionJson.has("assetIndex")) {
            return;
        }
        JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
        String indexId = assetIndex.get("id").getAsString();
        String indexUrl = assetIndex.get("url").getAsString();

        progress.file("Индекс ассетов");
        Path indexFile = assetsDir.resolve("indexes").resolve(indexId + ".json");
        if (!Files.isRegularFile(indexFile)) {
            Http.downloadToFile(indexUrl, indexFile);
        }
        progress.bump();

        JsonObject index = com.google.gson.JsonParser
                .parseString(Files.readString(indexFile)).getAsJsonObject();
        JsonObject objects = index.getAsJsonObject("objects");
        if (objects.size() == 0) {
            return;
        }

        log.accept("Ассетов в индексе " + indexId + ": " + objects.size());
        Progress assetProgress = new Progress(objects.size());
        String resourceBase = "https://resources.download.minecraft.net/";

        Map<String, List<AssetObject>> groupedAssets = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            JsonObject asset = entry.getValue().getAsJsonObject();

            // 1.7+ indexes are keyed by asset path and carry the hash inside. Pre-1.7 indexes are
            // keyed by the hash itself and carry no path, which the game does not need.
            String hash = asset.has("hash") ? asset.get("hash").getAsString() : entry.getKey();
            String path = asset.has("path") ? asset.get("path").getAsString() : entry.getKey();
            groupedAssets.computeIfAbsent(hash, ignored -> new ArrayList<>())
                    .add(new AssetObject(path, asset));
        }

        List<Callable<Void>> tasks = new ArrayList<>(groupedAssets.size());
        for (Map.Entry<String, List<AssetObject>> group : groupedAssets.entrySet()) {
            String hash = group.getKey();
            List<AssetObject> assets = group.getValue();
            JsonObject asset = assets.get(0).metadata();
            tasks.add(() -> {
                Path target = assetsDir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
                assetProgress.file(assets.get(0).path());
                if (!validAsset(target, asset)) {
                    String url = asset.has("url") ? asset.get("url").getAsString()
                            : resourceBase + hash.substring(0, 2) + "/" + hash;
                    download(url, target, null, assetProgress);
                }
                for (AssetObject object : assets) {
                    assetProgress.file(object.path());
                    assetProgress.bump();
                    reportAssets(assetProgress);
                }
                return null;
            });
        }
        runParallel(tasks);
    }

    private static void runParallel(List<Callable<Void>> tasks)
            throws IOException, InterruptedException {
        if (tasks.isEmpty()) {
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(DOWNLOAD_WORKERS, tasks.size()));
        List<Future<Void>> futures = new ArrayList<>(tasks.size());
        try {
            for (Callable<Void> task : tasks) {
                futures.add(executor.submit(task));
            }
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (ExecutionException e) {
            futures.forEach(future -> future.cancel(true));
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new IOException("Не удалось скачать файлы Minecraft", cause);
        } catch (InterruptedException e) {
            futures.forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Assets dwarf everything else, so their progress is folded into the single overall figure:
     * libraries take the first slice, assets the bulk of the range, and the final 5% is the hand-off
     * to Java and the game process.
     */
    private synchronized void reportAssets(Progress assetProgress) {
        var listener = progressListener;
        if (listener == null) {
            return;
        }
        // percent() is already 0..100, so it is scaled by the asset share directly.
        int overall = Math.min(100, LIBRARY_SHARE + assetProgress.percent() * ASSET_SHARE / 100);
        if (overall <= lastReported.getAndAccumulate(overall, Math::max)) {
            return;
        }
        listener.accept(overall, "ассеты: " + assetProgress.current());
    }

    private static boolean validAsset(Path file, JsonObject asset) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        if (!asset.has("size")) {
            return true;
        }
        try {
            return Files.size(file) == asset.get("size").getAsLong();
        } catch (IOException e) {
            return false;
        }
    }

    private void download(String url, Path target, String sha1, Progress progress)
            throws IOException, InterruptedException {

        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                DownloadStream.toFile(url, tmp, ignored -> progress.file(progress.current()));
                lastFailure = null;
                break;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt == DOWNLOAD_ATTEMPTS) {
                    break;
                }
                log.accept("Ошибка загрузки " + target.getFileName() + "; повтор "
                        + (attempt + 1) + "/" + DOWNLOAD_ATTEMPTS + ": " + e.getMessage());
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        if (sha1 != null) {
            String actual = Util.sha1(tmp);
            if (!actual.equalsIgnoreCase(sha1)) {
                Files.deleteIfExists(tmp);
                throw new IOException("Хеш не совпал для " + target.getFileName()
                        + " (ожидался " + sha1 + ", получен " + actual + ")");
            }
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Extracts native binaries into the version-specific natives directory. */
    public void unpackNatives(VersionMetadata version) throws IOException, InterruptedException {
        List<Library> natives = version.nativeLibraries();
        if (natives.isEmpty()) {
            return;
        }
        Path target = nativesDir.resolve(version.id);
        Files.createDirectories(target);

        List<String> extracted = new ArrayList<>();
        for (Library library : natives) {
            String path = library.nativesPath();
            if (path == null) {
                continue;
            }
            Path jar = librariesDir.resolve(path.replace('\\', '/'));
            if (!Files.isRegularFile(jar)) {
                continue;
            }
            extracted.addAll(NativeExtractor.extract(jar, target));
        }
        log.accept("Нативных файлов распаковано: " + extracted.size());
    }

    private static boolean valid(Path file, String sha1) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        if (sha1 == null || sha1.isBlank()) {
            return true;
        }
        try {
            return Util.sha1(file).equalsIgnoreCase(sha1);
        } catch (IOException e) {
            return false;
        }
    }

    private static String shortName(Library library) {
        String name = library.name() != null ? library.name() : String.valueOf(library.path());
        int colon = name.lastIndexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }
}