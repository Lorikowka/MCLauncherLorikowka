package com.mymods.mclauncher.p2p;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A listening socket that hands every accepted connection to a connector and relays the result.
 *
 * <p>Both ends of a tunnel are the same thing with a different connector, so there is one class
 * rather than a host and a guest: the host binds every interface and connects each arriving socket
 * to the integrated server on loopback, the guest binds loopback for Minecraft and connects to the
 * host. Swapping which side is which is then a change of two arguments.
 *
 * <p>A Minecraft session is one connection that lives for the whole session, so each connection gets
 * its own thread instead of a pool that would only ever hold one entry.
 */
public final class TunnelListener implements Closeable {

    /** Produces the socket an accepted connection is relayed to. Called on that connection's thread. */
    public interface Connector {
        Socket connect(Socket inbound) throws IOException;
    }

    private final ServerSocket server;
    private final Connector connector;
    private final Consumer<String> log;
    private final Thread acceptor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Every live connection, so closing the listener also drops the sessions still running. */
    private final List<Socket> live = new ArrayList<>();

    /**
     * @param requestedPort the port to bind, or 0 to let the operating system pick a free one
     * @throws IOException when the port cannot be bound, which for a guest means it is already in use
     */
    public TunnelListener(InetAddress bindAddress, int requestedPort, Connector connector,
                          Consumer<String> log) throws IOException {

        this.connector = connector;
        this.log = log;
        this.server = new ServerSocket();
        this.server.setReuseAddress(true);
        this.server.bind(new InetSocketAddress(bindAddress, requestedPort), 16);
        this.acceptor = new Thread(this::acceptLoop, "p2p-accept");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    /** The port actually bound, which matters when 0 was requested. */
    public int port() {
        return server.getLocalPort();
    }

    public boolean isClosed() {
        return closed.get();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            Socket inbound;
            try {
                inbound = server.accept();
            } catch (IOException e) {
                if (!closed.get()) {
                    log.accept("Приём соединений остановлен: " + e.getMessage());
                }
                return;
            }
            track(inbound);
            Thread session = new Thread(() -> serve(inbound), "p2p-session");
            session.setDaemon(true);
            session.start();
        }
    }

    private void serve(Socket inbound) {
        Socket outbound = null;
        try {
            inbound.setTcpNoDelay(true);
            outbound = connector.connect(inbound);
            outbound.setTcpNoDelay(true);
            StreamRelay.Counters counters = StreamRelay.relay(inbound, outbound, log);
            log.accept("Сессия закрыта: " + counters.toRight() + " байт туда, "
                    + counters.toLeft() + " байт обратно");
        } catch (IOException e) {
            log.accept("Сессия не установлена: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            forget(inbound);
            if (outbound != null) {
                forget(outbound);
                closeQuietly(outbound);
            }
            closeQuietly(inbound);
        }
    }

    private synchronized void track(Socket socket) {
        live.add(socket);
    }

    private synchronized void forget(Socket socket) {
        live.remove(socket);
    }

    /** Stops accepting and drops the sessions in flight, so a closed tunnel holds no port. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            server.close();
        } catch (IOException ignored) {
            // Already closed, which is the state we asked for.
        }
        List<Socket> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(live);
            live.clear();
        }
        snapshot.forEach(TunnelListener::closeQuietly);
        log.accept("Туннель на порту " + port() + " закрыт");
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed, which is the state we asked for.
        }
    }
}
