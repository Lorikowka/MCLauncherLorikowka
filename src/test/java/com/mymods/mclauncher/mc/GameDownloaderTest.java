package com.mymods.mclauncher.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameDownloaderTest {

    @Test
    void downloadsDifferentAssetsConcurrentlyAndDuplicateHashesOnce(@TempDir Path root)
            throws Exception {
        byte[] body = "shared asset bytes".getBytes(StandardCharsets.UTF_8);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        AtomicInteger resourceRequests = new AtomicInteger();
        CountDownLatch bothStarted = new CountDownLatch(2);
        ExecutorService serverThreads = Executors.newFixedThreadPool(4);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverThreads);
        server.createContext("/index", exchange -> respond(exchange, assetIndex(server, body)));
        server.createContext("/resource/", exchange -> {
            int nowActive = active.incrementAndGet();
            maximumActive.accumulateAndGet(nowActive, Math::max);
            resourceRequests.incrementAndGet();
            bothStarted.countDown();
            try {
                try {
                    bothStarted.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, body);
            } finally {
                active.decrementAndGet();
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            JsonObject versionJson = new JsonObject();
            versionJson.addProperty("id", "test-assets");
            versionJson.addProperty("mainClass", "TestMain");
            JsonObject assetIndex = new JsonObject();
            assetIndex.addProperty("id", "test");
            assetIndex.addProperty("url", "http://127.0.0.1:" + port + "/index");
            versionJson.add("assetIndex", assetIndex);
            versionJson.addProperty("assets", "test");

            Path versions = root.resolve("versions");
            Path libraries = root.resolve("libraries");
            Path assets = root.resolve("assets");
            Path natives = root.resolve("natives");
            VersionMetadata metadata = VersionMetadata.fromJson(versionJson);
                GameDownloader downloader = new GameDownloader(versions, libraries, assets, natives,
                    ignored -> { });
                List<Integer> progress = Collections.synchronizedList(new ArrayList<>());
                downloader.onProgress((percent, name) -> progress.add(percent));
                downloader.install(metadata, versionJson);

            String firstHash = hash('a');
            String secondHash = hash('b');
            assertTrue(maximumActive.get() >= 2, "asset requests should overlap");
            assertEquals(2, resourceRequests.get(), "the shared hash should be downloaded once");
                assertTrue(progress.stream().allMatch(percent -> percent <= 95),
                    "assets should leave the final 5% for launch preparation");
                assertTrue(isMonotonic(progress), "download progress should not move backwards");
            assertTrue(Files.isRegularFile(assets.resolve("objects").resolve("aa").resolve(firstHash)));
            assertTrue(Files.isRegularFile(assets.resolve("objects").resolve("bb").resolve(secondHash)));
        } finally {
            server.stop(0);
            serverThreads.shutdownNow();
        }
    }

    private static String assetIndex(HttpServer server, byte[] body) {
        int port = server.getAddress().getPort();
        JsonObject root = new JsonObject();
        JsonObject objects = new JsonObject();
        addAsset(objects, "first", hash('a'), body.length,
                "http://127.0.0.1:" + port + "/resource/first");
        addAsset(objects, "duplicate", hash('a'), body.length,
                "http://127.0.0.1:" + port + "/resource/duplicate");
        addAsset(objects, "second", hash('b'), body.length,
                "http://127.0.0.1:" + port + "/resource/second");
        root.add("objects", objects);
        return root.toString();
    }

    private static void addAsset(JsonObject objects, String name, String hash, int size, String url) {
        JsonObject asset = new JsonObject();
        asset.addProperty("hash", hash);
        asset.addProperty("size", size);
        asset.addProperty("url", url);
        objects.add(name, asset);
    }

    private static String hash(char prefix) {
        return String.valueOf(prefix).repeat(40);
    }

    private static boolean isMonotonic(List<Integer> progress) {
        int previous = -1;
        for (int current : progress) {
            if (current < previous) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}