package com.mymods.mclauncher.p2p;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the integrated server's port out of the lines Minecraft writes to {@code latest.log}.
 *
 * <p>Which line carries the port depends on the version and on whether the world was opened to LAN,
 * so every known wording is matched rather than one. A line that does not carry a port returns
 * empty instead of guessing: the tunnel has to reach the world on the first try, because Minecraft
 * offers the player a single connection attempt and shows nothing useful when it fails.
 */
public final class LogPortScanner {

    /**
     * The wordings seen so far, all of them confirmed against real logs except the LAN ones, which
     * only appear once a world is actually shared:
     *
     * <ul>
     *   <li>{@code Starting minecraft server on *:25565} — vanilla, printed when the server binds
     *       an address it considers reachable.
     *   <li>{@code Local game hosted on port 25565} — vanilla, after "Open to LAN".
     *   <li>{@code Starting integrated minecraft server on port 25565} — the singleplayer wording
     *       that names the port directly.
     * </ul>
     *
     * <p>A singleplayer world opened on its own prints no port at all. That was checked against four
     * real 1.19.2 runs, whose logs hold 125 server lines between them and not one of them a port.
     * {@link LocalPortProbe} exists because of that.
     */
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)\\b(?:hosted|served|listening|sharing)\\b.*?\\bport\\s+(\\d{1,5})\\b"),
            Pattern.compile("(?i)\\bserver\\b.*?\\bon\\s+\\S{0,40}?:(\\d{1,5})\\b"),
            Pattern.compile("(?i)\\bintegrated\\b.*?\\bport\\s+(\\d{1,5})\\b"));

    private LogPortScanner() {}

    /** The port the line announces, or empty when it announces none. */
    public static int findPort(String line) {
        if (line == null) {
            return -1;
        }
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                int port = parsePort(matcher.group(1));
                if (port > 0) {
                    return port;
                }
            }
        }
        return -1;
    }

    public static boolean announcesPort(String line) {
        return findPort(line) > 0;
    }

    /**
     * A port out of range is treated as absent rather than clamped, so a version that formats its
     * line differently produces "no port" and lets another way of finding it run.
     */
    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            return port >= 1 && port <= 65535 ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
