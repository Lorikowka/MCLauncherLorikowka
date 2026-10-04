package com.mymods.mclauncher.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ServerAddressTest {

    @Test
    void normalizesDomainAndPort() {
        assertEquals("play.example.net:25565",
                ServerAddress.normalize(" play.example.net:025565 "));
    }

    @Test
    void acceptsIpv4AndDefaultPort() {
        assertEquals("192.168.1.20", ServerAddress.normalize("192.168.1.20"));
    }

    @Test
    void acceptsBracketedIpv6() {
        assertEquals("[::1]:25565", ServerAddress.normalize("[::1]:25565"));
    }

    @Test
    void rejectsMissingHostAndMalformedPorts() {
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.normalize(":25565"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerAddress.normalize("play.example.net:abc"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerAddress.normalize("play.example.net:65536"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerAddress.normalize("2001:db8::1"));
    }
}