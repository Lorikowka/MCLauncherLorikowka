package com.mymods.mclauncher.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tunnel, exercised over real sockets on loopback: no transport is mocked, so a broken relay
 * fails here rather than in front of a user waiting to join a world.
 */
class P2pServiceTest {

    private static final long WAIT_SECONDS = 20;

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final Consumer<String> logTo = log::add;

    @Test
    void bytesFlowBothWaysThroughTheTunnel() throws Exception {
        try (EchoServer world = new EchoServer();
                TunnelListener host = new TunnelListener(InetAddress.getLoopbackAddress(), 0,
                        inbound -> new Socket(InetAddress.getLoopbackAddress(), world.port()), logTo);
                TunnelListener guest = new TunnelListener(InetAddress.getLoopbackAddress(), 0,
                        inbound -> new Socket(InetAddress.getLoopbackAddress(), host.port()), logTo)) {

            byte[] request = "пакет от гостя".getBytes(StandardCharsets.UTF_8);
            byte[] reply = roundTrip(guest.port(), request);

            assertArrayEqualsBytes(request, reverse(reply));
            assertTrue(echoed(world), "мир не получил пакет гостя");
        }
    }

    @Test
    void guestJoinsThroughTheAddressTheServiceHandsToMinecraft() throws Exception {
        P2pService service = new P2pService(logTo, new P2pService.Listener() {
        });
        try (EchoServer world = new EchoServer()) {
            service.join("127.0.0.1", world.port(), 0);
            String address = service.guestAddress();
            assertTrue(address.startsWith("127.0.0.1:"), address);
            assertArrayEqualsBytes("через туннель".getBytes(StandardCharsets.UTF_8),
                    reverse(roundTrip(Integer.parseInt(address.substring(address.indexOf(':') + 1)),
                            "через туннель".getBytes(StandardCharsets.UTF_8))));
        } finally {
            service.close();
        }
    }

    @Test
    void aGuestPortAlreadyInUseIsReportedRatherThanSilentlyMoved() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
            P2pService service = new P2pService(logTo, new P2pService.Listener() {
            });
            try {
                service.join("127.0.0.1", 25565, occupied.getLocalPort());
                assertFalse(service.isJoining(), "гость не должен считаться подключённым");
            } catch (IOException expected) {
                assertTrue(expected.getMessage() != null, "причина должна быть названа");
            } finally {
                service.close();
            }
        }
    }

    @Test
    void closingTheTunnelFreesThePortForTheNextSession() throws Exception {
        P2pService service = new P2pService(logTo, new P2pService.Listener() {
        });
        int port;
        try (EchoServer world = new EchoServer()) {
            service.join("127.0.0.1", world.port(), 0);
            port = Integer.parseInt(service.guestAddress().substring(
                    service.guestAddress().indexOf(':') + 1));
        }
        service.close();

        try (ServerSocket reused = new ServerSocket()) {
            reused.setReuseAddress(true);
            reused.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1);
        }
    }

    @Test
    void theWorldPortIsFoundByWhatStoppedBeingFree() throws Exception {
        LocalPortProbe probe = new LocalPortProbe();
        Set<Integer> free = probe.snapshot();
        assertFalse(free.isEmpty(), "на этой машине не нашлось свободных портов");

        int taken = free.iterator().next();
        try (ServerSocket world = new ServerSocket()) {
            world.setReuseAddress(true);
            world.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), taken), 1);

            assertEquals(taken, probe.worldPort(free, Set.of()));
        }
    }

    @Test
    void anUnchangedMachineYieldsNoPortRatherThanAGuess() {
        LocalPortProbe probe = new LocalPortProbe();
        Set<Integer> free = probe.snapshot();
        assertEquals(-1, probe.worldPort(free, Set.of()));
    }

    @Test
    void twoPortsTakenAtOnceAreNotResolvedToOneOfThem() throws Exception {
        LocalPortProbe probe = new LocalPortProbe();
        List<Integer> free = probe.snapshot().stream().limit(2).toList();
        assertEquals(2, free.size());
        try (ServerSocket first = new ServerSocket();
                ServerSocket second = new ServerSocket()) {
            first.setReuseAddress(true);
            second.setReuseAddress(true);
            first.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), free.get(0)), 1);
            second.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), free.get(1)), 1);

            assertEquals(-1, probe.worldPort(Set.copyOf(free), Set.of()));
        }
    }

    @Test
    void theLogNamesThePortWhenMinecraftPrintsIt() {
        assertEquals(25565, LogPortScanner.findPort(
                "[Server thread/INFO] [minecraft/DedicatedServer]: Starting minecraft server on *:25565"));
        assertEquals(45678, LogPortScanner.findPort(
                "[Render thread/INFO] [minecraft/Minecraft]: Local game hosted on port 45678"));
        assertEquals(25566, LogPortScanner.findPort(
                "[Server thread/INFO] [minecraft/IntegratedServer]: "
                        + "Starting integrated minecraft server on port 25566"));
    }

    @Test
    void linesFromRealRunsYieldNoPort() {
        // Verbatim lines from four real 1.19.2 runs. Not one of them names the port, which is the
        // whole reason LocalPortProbe exists; a parser that "found" one of these would be guessing.
        List<String> real = List.of(
            "[28сент.2026 15:07:46.399] [Server thread/INFO] "
                + "[net.minecraft.client.server.IntegratedServer/]: "
                + "Starting integrated minecraft server version 1.19.2",
            "[28сент.2026 15:07:46.400] [Server thread/INFO] "
                + "[net.minecraft.server.MinecraftServer/]: Generating keypair",
            "[28сент.2026 15:07:47.038] [Server thread/INFO] "
                + "[net.minecraft.server.MinecraftServer/]: "
                + "Preparing start region for dimension minecraft:overworld",
            "[28сент.2026 15:07:57.848] [Render thread/INFO] "
                + "[net.minecraft.server.level.progress.LoggerChunkProgressListener/]: "
                + "Preparing spawn area: 0%",
            "[06окт.2026 19:58:04.021] [Server thread/INFO] "
                + "[net.minecraft.server.players.PlayerList/]: "
                + "Dev[local:E:e4580c1c] logged in with entity id 96 at (-31.43, -60.0, 48.13)",
            "[28сент.2026 15:09:14.897] [Server thread/INFO] "
                + "[net.minecraft.client.server.IntegratedServer/]: Saving and pausing game...",
            "[28сент.2026 15:09:18.028] [Server thread/INFO] "
                + "[net.minecraft.server.MinecraftServer/]: Dev left the game",
            "[28сент.2026 15:09:18.055] [Server thread/INFO] "
                + "[net.minecraft.server.MinecraftServer/]: "
                + "Stopping singleplayer server as player logged out",
            "[28сент.2026 15:09:23.176] [Server thread/INFO] "
                + "[net.minecraft.server.MinecraftServer/]: "
                + "Saving chunks for level 'ServerLevel[New World]'/minecraft:overworld",
            "[28сент.2026 15:09:18.056] [Server thread/INFO] "
                + "[net.minecraft.server.MinecraftServer/]: Stopping server");

        for (String line : real) {
            assertEquals(-1, LogPortScanner.findPort(line), line);
            assertFalse(LogPortScanner.announcesPort(line), line);
        }
    }

    @Test
    void aPortOutOfRangeCountsAsNoPort() {
        assertEquals(-1, LogPortScanner.findPort("Local game hosted on port 99999"));
        assertEquals(-1, LogPortScanner.findPort(null));
    }

    @Test
    void theWatcherReportsAWorldOpeningAndClosing(@TempDir Path gameDir) throws Exception {
        Path logFile = gameDir.resolve("logs").resolve("latest.log");
        Files.createDirectories(logFile.getParent());

        List<WorldLogWatcher.Event> events = new CopyOnWriteArrayList<>();
        try (WorldLogWatcher watcher = new WorldLogWatcher(gameDir, events::add, logTo)) {
            watcher.start();
            Files.writeString(logFile, "", StandardCharsets.UTF_8);

            append(logFile, "[Server thread/INFO] [net.minecraft.client.server.IntegratedServer/]: "
                    + "Starting integrated minecraft server version 1.19.2\n");
            await(events, WorldLogWatcher.Event.WORLD_OPENED);
            assertTrue(watcher.isWorldOpen());

            append(logFile, "[Server thread/INFO] [net.minecraft.server.MinecraftServer/]: "
                    + "Local game hosted on port 45678\n");
            await(events, WorldLogWatcher.Event.PORT_ANNOUNCED);

            append(logFile, "[Server thread/INFO] [net.minecraft.server.MinecraftServer/]: "
                    + "Stopping singleplayer server as player logged out\n");
            await(events, WorldLogWatcher.Event.WORLD_CLOSED);
            assertFalse(watcher.isWorldOpen());
        }
    }

    @Test
    void theWatcherFollowsALogTheGameRotates(@TempDir Path gameDir) throws Exception {
        Path logFile = gameDir.resolve("logs").resolve("latest.log");
        Path rotated = gameDir.resolve("logs").resolve("2026-10-04-1.log");
        Files.createDirectories(logFile.getParent());

        List<WorldLogWatcher.Event> events = new CopyOnWriteArrayList<>();
        try (WorldLogWatcher watcher = new WorldLogWatcher(gameDir, events::add, logTo)) {
            watcher.start();
            Files.writeString(logFile, "[Server thread/INFO] "
                    + "[net.minecraft.client.server.IntegratedServer/]: "
                    + "Starting integrated minecraft server version 1.19.2\n", StandardCharsets.UTF_8);
            await(events, WorldLogWatcher.Event.WORLD_OPENED);

            Files.move(logFile, rotated);
            Files.writeString(logFile, "[Server thread/INFO] [net.minecraft.server.MinecraftServer/]: "
                    + "Preparing start region for dimension minecraft:overworld\n",
                    StandardCharsets.UTF_8);
            await(events, WorldLogWatcher.Event.WORLD_OPENED);

            append(logFile, "[Server thread/INFO] [net.minecraft.server.MinecraftServer/]: "
                    + "Stopping server\n");
            await(events, WorldLogWatcher.Event.WORLD_CLOSED);
        }
    }

    @Test
    void aWorldClosedTwiceIsOnlyReportedOnce(@TempDir Path gameDir) throws Exception {
        Path logFile = gameDir.resolve("logs").resolve("latest.log");
        Files.createDirectories(logFile.getParent());

        List<WorldLogWatcher.Event> events = new CopyOnWriteArrayList<>();
        try (WorldLogWatcher watcher = new WorldLogWatcher(gameDir, events::add, logTo)) {
            watcher.start();
            Files.writeString(logFile, "", StandardCharsets.UTF_8);

            append(logFile, "[Server thread/INFO] [net.minecraft.client.server.IntegratedServer/]: "
                    + "Starting integrated minecraft server version 1.19.2\n");
            await(events, WorldLogWatcher.Event.WORLD_OPENED);

            append(logFile, "[Server thread/INFO] [net.minecraft.server.MinecraftServer/]: "
                    + "Stopping server\nStopping server\n");
            await(events, WorldLogWatcher.Event.WORLD_CLOSED);

            Thread.sleep(600);
            assertEquals(1, events.stream()
                    .filter(WorldLogWatcher.Event.WORLD_CLOSED::equals).count());
            assertFalse(watcher.isWorldOpen());
        }
    }

    @Test
    void theWatcherReportsAPortEvenWithoutAWorldItRecognised(@TempDir Path gameDir) throws Exception {
        Path logFile = gameDir.resolve("logs").resolve("latest.log");
        Files.createDirectories(logFile.getParent());

        List<WorldLogWatcher.Event> events = new CopyOnWriteArrayList<>();
        try (WorldLogWatcher watcher = new WorldLogWatcher(gameDir, events::add, logTo)) {
            watcher.start();
            Files.writeString(logFile, "", StandardCharsets.UTF_8);

            append(logFile, "что-то без знакомых слов\n");
            append(logFile, "[Render thread/INFO] [minecraft/Minecraft]: "
                    + "Local game hosted on port 45678\n");
            await(events, WorldLogWatcher.Event.PORT_ANNOUNCED);
        }
    }

    @Test
    void theLocalAddressesAreRealOnes() {
        // Nothing to assert about which adapter wins, only that the list is not claiming an address
        // that could not be connected to.
        for (String address : LocalAddresses.ipv4()) {
            assertNotEquals("127.0.0.1", address);
            assertTrue(address.matches("\\d{1,3}(\\.\\d{1,3}){3}"), address);
        }
    }

    @Test
    void aMissingLogIsNotAnError() throws Exception {
        // The game has not written anything yet, which is the normal state right after launch.
        List<WorldLogWatcher.Event> events = new CopyOnWriteArrayList<>();
        try (WorldLogWatcher watcher = new WorldLogWatcher(
                Path.of(System.getProperty("java.io.tmpdir")).resolve("mclauncher-no-such-game"),
                events::add, logTo)) {
            watcher.start();
            Thread.sleep(700);
            assertTrue(events.isEmpty());
            assertFalse(watcher.isWorldOpen());
        }
    }

    @Test
    void theServiceStopsCleanlyWhenItNeverHostedAnything() {
        P2pService service = new P2pService(logTo, new P2pService.Listener() {
        });
        service.stop();
        service.stop();
        assertFalse(service.isBusy());
        assertEquals(-1, service.worldPort());
        assertEquals("", service.localInvite());
        assertEquals("", service.guestAddress());
    }

    @Test
    void anInviteIsOfferedOnlyOnceAWorldPortIsKnown() {
        P2pService service = new P2pService(logTo, new P2pService.Listener() {
        });
        assertEquals("", service.localInvite());
        service.close();
    }

    private static void append(Path logFile, String line) throws IOException {
        Files.writeString(logFile, line, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND, java.nio.file.StandardOpenOption.CREATE);
    }

    private static void await(List<WorldLogWatcher.Event> events, WorldLogWatcher.Event wanted)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (events.contains(wanted)) {
                return;
            }
            Thread.sleep(25);
        }
        assertTrue(events.contains(wanted), "не дождались " + wanted + ", было: " + events);
    }

    private static byte[] roundTrip(int port, byte[] request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000);
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();
            byte[] buffer = new byte[request.length];
            int read = 0;
            while (read < buffer.length) {
                int count = socket.getInputStream().read(buffer, read, buffer.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            return buffer;
        }
    }

    private static byte[] reverse(byte[] bytes) {
        byte[] copy = bytes.clone();
        for (int i = 0; i < copy.length / 2; i++) {
            byte swap = copy[i];
            copy[i] = copy[copy.length - 1 - i];
            copy[copy.length - 1 - i] = swap;
        }
        return copy;
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertEquals(new String(expected, StandardCharsets.UTF_8),
                new String(actual, StandardCharsets.UTF_8));
    }

    private static boolean echoed(EchoServer world) throws InterruptedException {
        return world.awaitEcho();
    }

    /** Stands in for the integrated server: takes bytes in and hands them back reversed. */
    private static final class EchoServer implements AutoCloseable {

        private final ServerSocket server;
        private final CountDownLatch echoed = new CountDownLatch(1);

        EchoServer() throws IOException {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4);
            Thread thread = new Thread(this::serve, "echo-server");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        boolean awaitEcho() throws InterruptedException {
            return echoed.await(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    byte[] buffer = new byte[4096];
                    int count = client.getInputStream().read(buffer);
                    if (count > 0) {
                        byte[] reply = reverse(java.util.Arrays.copyOf(buffer, count));
                        client.getOutputStream().write(reply);
                        client.getOutputStream().flush();
                        echoed.countDown();
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
