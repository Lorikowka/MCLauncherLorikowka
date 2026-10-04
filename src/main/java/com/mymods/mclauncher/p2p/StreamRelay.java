package com.mymods.mclauncher.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Copies bytes both ways between an accepted connection and an outgoing one until the pair is done.
 *
 * <p>Minecraft speaks plain TCP and does not half-close: when a player leaves, the whole socket
 * goes. So the connection is torn down as soon as either direction finishes, rather than waiting
 * for the other one, which would otherwise hold a finished session open until its peer noticed.
 */
public final class StreamRelay {

    /** Large enough that a chunked world transfer does not turn into one syscall per packet. */
    private static final int BUFFER = 32 * 1024;

    /** Bytes handed over in each direction, so a caller can tell an idle tunnel from a working one. */
    public record Counters(long toRight, long toLeft) {}

    private StreamRelay() {}

    /**
     * Relays until either side closes, then closes both sockets.
     *
     * @param log receives one line per direction that ended with an error, never per chunk
     */
    public static Counters relay(Socket left, Socket right, Consumer<String> log)
            throws InterruptedException {

        AtomicLong toRight = new AtomicLong();
        AtomicLong toLeft = new AtomicLong();
        CountDownLatch finished = new CountDownLatch(2);

        pump(left, right, toRight, log, finished, "guest->world");
        pump(right, left, toLeft, log, finished, "world->guest");

        // The first direction to end closes both sockets, so the second one cannot sit in a blocking
        // read for ever. Waiting for both therefore returns as soon as the pair is torn down.
        finished.await();
        finished.await();
        return new Counters(toRight.get(), toLeft.get());
    }

    private static void pump(Socket from, Socket to, AtomicLong counter, Consumer<String> log,
                             CountDownLatch finished, String direction) {

        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[BUFFER];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    if (count == 0) {
                        continue;
                    }
                    out.write(buffer, 0, count);
                    out.flush();
                    counter.addAndGet(count);
                }
            } catch (IOException e) {
                // A peer that vanishes mid-transfer is ordinary: Minecraft drops the connection when
                // a player leaves the world, and the other direction then fails the same way.
                log.accept(direction + ": " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " — " + e.getMessage()));
            } finally {
                if (finished.getCount() == 1) {
                    closeQuietly(from);
                    closeQuietly(to);
                }
                finished.countDown();
            }
        }, "p2p-relay-" + direction);
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already broken, which is the state we were trying to reach.
        }
    }
}
