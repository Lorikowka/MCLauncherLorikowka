package com.mymods.mclauncher.mc;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Parses the host[:port] form accepted by Minecraft's multiplayer quick play. */
public final class ServerAddress {

    private ServerAddress() {}

    public static String normalize(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Укажи адрес сервера.");
        }
        String address = input.trim();
        if (address.isEmpty() || address.length() > 255
                || address.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Укажи адрес сервера без пробелов.");
        }

        String host;
        String portText = null;
        boolean ipv6 = address.startsWith("[");
        if (ipv6) {
            int closingBracket = address.indexOf(']');
            if (closingBracket < 0 || address.indexOf('[', 1) >= 0
                    || address.indexOf(']', closingBracket + 1) >= 0) {
                throw invalidAddress();
            }
            host = address.substring(1, closingBracket);
            if (!host.matches("[0-9A-Fa-f:.]+") || !host.contains(":")) {
                throw invalidAddress();
            }
            try {
                if (!(InetAddress.getByName(host) instanceof Inet6Address)) {
                    throw invalidAddress();
                }
            } catch (UnknownHostException e) {
                throw invalidAddress();
            }
            String suffix = address.substring(closingBracket + 1);
            if (!suffix.isEmpty()) {
                if (!suffix.startsWith(":")) {
                    throw invalidAddress();
                }
                portText = suffix.substring(1);
            }
        } else {
            if (address.indexOf('[') >= 0 || address.indexOf(']') >= 0) {
                throw invalidAddress();
            }
            int colon = address.indexOf(':');
            if (colon >= 0) {
                if (colon != address.lastIndexOf(':')) {
                    throw new IllegalArgumentException("IPv6-адрес укажи в квадратных скобках.");
                }
                host = address.substring(0, colon);
                portText = address.substring(colon + 1);
            } else {
                host = address;
            }
            try {
                host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            } catch (IllegalArgumentException e) {
                throw invalidAddress();
            }
            if (!validDnsName(host)) {
                throw invalidAddress();
            }
        }

        if (portText != null) {
            if (portText.isEmpty() || !portText.chars().allMatch(Character::isDigit)) {
                throw invalidPort();
            }
            try {
                int port = Integer.parseInt(portText);
                if (port < 1 || port > 65535) {
                    throw invalidPort();
                }
                portText = Integer.toString(port);
            } catch (NumberFormatException e) {
                throw invalidPort();
            }
        }

        String normalizedHost = ipv6 ? "[" + host + "]" : host;
        return portText == null ? normalizedHost : normalizedHost + ":" + portText;
    }

    private static boolean validDnsName(String host) {
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (name.isEmpty()) {
            return false;
        }
        for (String label : name.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-")
                    || label.endsWith("-")) {
                return false;
            }
        }
        return true;
    }

    private static IllegalArgumentException invalidAddress() {
        return new IllegalArgumentException(
                "Проверь адрес сервера, например play.example.net:25565.");
    }

    private static IllegalArgumentException invalidPort() {
        return new IllegalArgumentException("Порт должен быть числом от 1 до 65535.");
    }
}