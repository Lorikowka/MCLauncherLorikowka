package com.mymods.mclauncher.p2p;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The machine's addresses on the networks a friend could reach it from.
 *
 * <p>Used only to fill in the invite code for the same-network case. Anything that has to work
 * across the internet gets its address from STUN instead, because a private address is exactly the
 * one a router will not let in.
 */
public final class LocalAddresses {

    private LocalAddresses() {}

    /**
     * Every non-loopback IPv4 address, most likely to be the one a friend would use first.
     *
     * <p>A virtual adapter from a VPN or a hypervisor sorts after the real ones because it tends to
     * carry a name the user would not recognise, so the list stays usable without picking by hand.
     */
    public static List<String> ipv4() {
        List<String> addresses = new ArrayList<>();
        for (InetAddress address : all()) {
            if (address instanceof Inet4Address && !address.isLoopbackAddress()
                    && !address.isLinkLocalAddress() && !address.isAnyLocalAddress()) {
                addresses.add(address.getHostAddress());
            }
        }
        Collections.sort(addresses, LocalAddresses::byOctet);
        return addresses;
    }

    /** Numeric order, so 192.168.1.2 comes before 192.168.1.10 rather than after it. */
    private static int byOctet(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int diff = Integer.parseInt(a[i]) - Integer.parseInt(b[i]);
            if (diff != 0) {
                return diff;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    /** The address to put in an invite code, or an empty string when the machine is offline. */
    public static String preferredIpv4() {
        List<String> addresses = ipv4();
        return addresses.isEmpty() ? "" : addresses.get(0);
    }

    private static List<InetAddress> all() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nic.isLoopback() || !nic.isUp()) {
                    continue;
                }
                Collections.list(nic.getInetAddresses()).forEach(addresses::add);
            }
        } catch (SocketException e) {
            // No interfaces readable: treated as offline rather than as a failure to report.
            return List.of();
        }
        return addresses;
    }
}
