package com.mymods.mclauncher.p2p;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Runs the tunnel on either side of a session and owns everything one session needs.
 *
 * <p>The host half waits for a world to open, works out which port it landed on, and listens for the
 * guest. The guest half listens on loopback for Minecraft and connects out to the host. Nothing here
 * knows how the two halves will later find each other across the internet; that is ICE and
 * signalling, and this class is what they drive.
 *
 * <p>All of it runs off the UI thread and reports back through {@link Listener}.
 */
public final class P2pService implements Closeable {

    /** What the UI needs to hear about. Delivered on background threads. */
    public interface Listener {
        default void status(String line) {}

        default void inviteReady(String invite) {}

        default void guestReady(String localPort) {}

        default void failed(String what, Exception e) {}
    }

    /** Loopback only: a session should not be reachable from the network by accident. */
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    /** What a host binds, so a friend on the same network can reach it. */
    private static final InetAddress ANY_INTERFACE = anyInterface();

    private static InetAddress anyInterface() {
        try {
            return InetAddress.getByAddress(new byte[4]);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("0.0.0.0 must be a valid address", e);
        }
    }

    /** How long to keep looking for the world's port before saying it was not found. */
    private static final long PORT_SEARCH_MILLIS = 20_000;
    private static final long PORT_SEARCH_STEP_MILLIS = 500;

    private final Consumer<String> log;
    private final Listener listener;
    private final LocalPortProbe probe = new LocalPortProbe();

    private WorldLogWatcher watcher;
    private TunnelListener tunnel;

    /** Free ports recorded before the world existed, which is the only moment they mean anything. */
    private volatile Set<Integer> freeWellKnown;
    private volatile Set<Integer> freeEphemeral;
    private volatile Thread ephemeralSnapshot;
    private volatile Thread portSearch;

    private volatile int worldPort = -1;
    private volatile boolean hosting;
    private volatile boolean joining;

    public P2pService(Consumer<String> log, Listener listener) {
        this.log = log;
        this.listener = listener;
    }

    /** The integrated server's port, or -1 while it is unknown. */
    public int worldPort() {
        return worldPort;
    }

    public boolean isHosting() {
        return hosting;
    }

    public boolean isJoining() {
        return joining;
    }

    public boolean isBusy() {
        return hosting || joining;
    }

    /**
     * Offers the game to a friend and starts watching for a world.
     *
     * <p>Must be called while the game is starting but before its world opens, because the ports are
     * only recognisable as the game's while they are still free.
     */
    public void host(Path gameDir) {
        stopHosting();
        hosting = true;
        freeWellKnown = probe.snapshot();
        freeEphemeral = null;
        log.accept("Свободных портов записано: " + freeWellKnown.size());

        // Walking the ephemeral range takes about three seconds, which is too long to spend on the
        // way to the world but easily fits between the game starting and the world opening.
        ephemeralSnapshot = background("p2p-ephemeral-snapshot", () -> {
            Set<Integer> snapshot = probe.snapshot(LocalPortProbe.ephemeralRange());
            freeEphemeral = snapshot;
            log.accept("Эфемерных портов записано: " + snapshot.size());
        });

        watcher = new WorldLogWatcher(gameDir, this::onLogEvent, log);
        watcher.start();
        listener.status("Открой мир в игре — я поймаю порт");
    }

    /**
     * Listens on loopback for Minecraft and connects every connection to the host.
     *
     * @param localPort the port Minecraft is told to join, 0 to let the operating system choose
     * @throws IOException when the port is taken, which for a guest means an earlier session is
     *         still alive and has to be stopped first
     */
    public void join(String host, int hostPort, int localPort) throws IOException {
        stopJoining();
        joining = true;
        tunnel = new TunnelListener(LOOPBACK, localPort,
                inbound -> connectToHost(inbound, host, hostPort), log);
        listener.guestReady("127.0.0.1:" + tunnel.port());
        listener.status("Жду подключения Minecraft к 127.0.0.1:" + tunnel.port());
    }

    public void stopHosting() {
        hosting = false;
        worldPort = -1;
        closeWatcher();
        stopPortSearch();
        if (tunnel != null) {
            tunnel.close();
            tunnel = null;
        }
        freeWellKnown = null;
        freeEphemeral = null;
        Thread snapshot = ephemeralSnapshot;
        if (snapshot != null) {
            snapshot.interrupt();
            ephemeralSnapshot = null;
        }
    }

    public void stopJoining() {
        joining = false;
        if (tunnel != null) {
            tunnel.close();
            tunnel = null;
        }
    }

    public void stop() {
        stopHosting();
        stopJoining();
    }

    /** The address a friend on the same network would use, or an empty string when there is none. */
    public String localInvite() {
        return tunnel == null ? "" : LocalAddresses.preferredIpv4() + ":" + tunnel.port();
    }

    /** The address Minecraft on this machine joins when it is the guest. */
    public String guestAddress() {
        return tunnel == null || !joining ? "" : "127.0.0.1:" + tunnel.port();
    }

    private Socket connectToHost(Socket inbound, String host, int hostPort) throws IOException {
        Socket outbound = new Socket();
        outbound.connect(new InetSocketAddress(host, hostPort), 10_000);
        log.accept("Гость подключился, его трафик идёт на " + host + ":" + hostPort);
        return outbound;
    }

    private void onLogEvent(WorldLogWatcher.Event event) {
        switch (event) {
            case WORLD_OPENED -> {
                listener.status("Мир открыт, ищу порт ...");
                startPortSearch();
            }
            case WORLD_CLOSED -> {
                worldPort = -1;
                stopPortSearch();
                listener.status("Мир закрыт");
            }
            case PORT_ANNOUNCED -> listener.status("Порт назван в логе, проверяю ...");
            default -> {
            }
        }
    }

    /**
     * Looks for the world's port until it turns up or the search runs out of time.
     *
     * <p>The port appears a moment after the log says the world is open, and the log itself usually
     * never names it, so this polls rather than reacting to a single event.
     */
    private void startPortSearch() {
        stopPortSearch();
        portSearch = background("p2p-port-search", () -> {
            long deadline = System.nanoTime() + PORT_SEARCH_MILLIS * 1_000_000L;
            while (hosting && System.nanoTime() < deadline) {
                int found = probe.worldPort(freeWellKnown, freeEphemeral);
                if (found > 0) {
                    publishWorld(found);
                    return;
                }
                sleep(PORT_SEARCH_STEP_MILLIS);
            }
            if (hosting) {
                listener.failed("Порт мира не найден", new IOException(
                        "Minecraft не сказал, на каком порте открыл мир, и ни один известный порт не "
                                + "занят. Без этого пригласить друга не получится."));
            }
        });
    }

    private void stopPortSearch() {
        Thread search = portSearch;
        if (search != null) {
            search.interrupt();
            portSearch = null;
        }
    }

    private void publishWorld(int worldPort) {
        if (!hosting) {
            return;
        }
        this.worldPort = worldPort;
        try {
            openTunnel(worldPort);
        } catch (IOException e) {
            listener.failed("Не удалось открыть туннель", e);
        }
    }

    private void openTunnel(int worldPort) throws IOException {
        if (tunnel != null) {
            tunnel.close();
        }
        tunnel = new TunnelListener(ANY_INTERFACE, 0,
                inbound -> new Socket(LOOPBACK, worldPort), log);
        log.accept("Туннель на порту " + tunnel.port() + " смотрит в мир на " + worldPort);

        String invite = localInvite();
        if (invite.isEmpty()) {
            listener.failed("Нет адреса в сети", new IOException(
                    "Ни одного адреса в локальной сети — пригласить друга нечем."));
            return;
        }
        listener.status("Приглашение готово: " + invite);
        listener.inviteReady(invite);
    }

    private void closeWatcher() {
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
    }

    private Thread background(String name, Runnable body) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (RuntimeException e) {
                if (!hosting && !joining) {
                    return;
                }
                listener.failed("Ошибка подготовки туннеля", e);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }
}
