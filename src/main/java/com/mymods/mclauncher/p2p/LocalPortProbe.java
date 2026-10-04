package com.mymods.mclauncher.p2p;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the port the integrated server bound by noticing which port stopped being free.
 *
 * <p>Minecraft does not print that port when a singleplayer world is opened on its own — this was
 * checked against four real 1.19.2 runs, and not one of their 125 server lines mentions a port. The
 * game still has to listen on loopback for its own "Open to LAN" button to work, so the port is
 * there to be found: everything free before the world opens and taken afterwards is what the game
 * took, and there is exactly one such port in the ranges worth looking at.
 *
 * <p>The well-known ports are checked first because they cost twenty-five binds. The ephemeral range
 * is only walked when that finds nothing, since it is three orders of magnitude larger.
 */
public final class LocalPortProbe {

    /**
     * Vanilla's own candidate list, taken from the ports it is willing to serve a LAN world on.
     * The gaps are in that list too: 25572 to 25574 and the range above 25590 are not used.
     */
    private static final int[] WELL_KNOWN = {
        25565, 25566, 25567, 25568, 25569, 25570, 25571,
        25575, 25576, 25577, 25578, 25579,
        25580, 25581, 25582, 25583, 25584, 25585, 25586, 25587, 25588,
        25589, 25590
    };

    /** Where an operating system hands out a port when the game asks for an arbitrary one. */
    private static final int EPHEMERAL_FROM = 49152;
    private static final int EPHEMERAL_TO = 65535;

    private final InetAddress loopback;

    public LocalPortProbe() {
        this(loopback());
    }

    public LocalPortProbe(InetAddress loopback) {
        this.loopback = loopback;
    }

    private static InetAddress loopback() {
        return InetAddress.getLoopbackAddress();
    }

    /**
     * The ports that are free right now and therefore worth watching.
     *
     * <p>Must be taken before the world opens: afterwards the port the game took is no longer free,
     * and there is nothing left to compare against.
     */
    public Set<Integer> snapshot() {
        return snapshot(wellKnown());
    }

    /** The well-known ports as a list, exposed so a caller can snapshot them by hand. */
    public static List<Integer> wellKnown() {
        List<Integer> ports = new ArrayList<>(WELL_KNOWN.length);
        for (int port : WELL_KNOWN) {
            ports.add(port);
        }
        return ports;
    }

    /** Same, over an explicit port list, so the ephemeral range can be asked for separately. */
    public Set<Integer> snapshot(List<Integer> ports) {
        Set<Integer> free = new LinkedHashSet<>();
        for (int port : ports) {
            if (isFree(port)) {
                free.add(port);
            }
        }
        return free;
    }

    /**
     * The watched ports that are taken now.
     *
     * <p>An empty answer means the game is listening somewhere outside what was watched, or not
     * listening at all. More than one means something else on the machine took a port at the same
     * moment, so no single answer can be trusted.
     */
    public List<Integer> takenSince(Set<Integer> free) {
        List<Integer> taken = new ArrayList<>();
        if (free == null) {
            return taken;
        }
        for (int port : free) {
            if (!isFree(port)) {
                taken.add(port);
            }
        }
        return taken;
    }

    /**
     * The single port the game most likely took, or -1 when the answer would be a guess.
     *
     * <p>Well-known ports are checked first, because a well-known port being taken means a Minecraft
     * server is on it far more often than not. Either set may be null or empty, which is what a
     * snapshot still in progress looks like.
     */
    public int worldPort(Set<Integer> freeBeforeWellKnown, Set<Integer> freeBeforeEphemeral) {
        List<Integer> wellKnown = takenSince(freeBeforeWellKnown);
        if (wellKnown.size() == 1) {
            return wellKnown.get(0);
        }
        if (!wellKnown.isEmpty()) {
            return -1;
        }
        List<Integer> ephemeral = takenSince(freeBeforeEphemeral);
        return ephemeral.size() == 1 ? ephemeral.get(0) : -1;
    }

    /** The ephemeral range, exposed so a caller can snapshot it before the game starts. */
    public static List<Integer> ephemeralRange() {
        List<Integer> ports = new ArrayList<>(EPHEMERAL_TO - EPHEMERAL_FROM + 1);
        for (int port = EPHEMERAL_FROM; port <= EPHEMERAL_TO; port++) {
            ports.add(port);
        }
        return ports;
    }

    /**
     * Whether the port can be bound on loopback, which is what "free" means here.
     *
     * <p>Reuse is set so a port left in TIME_WAIT by a previous session still counts as free:
     * nothing is listening on it, and the game may well pick it.
     */
    public boolean isFree(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(loopback, port), 1);
            return true;
        } catch (IOException taken) {
            return false;
        }
    }
}
