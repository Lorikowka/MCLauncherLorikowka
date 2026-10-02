package com.mymods.mclauncher.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class OfflineSessionTest {

    @Test
    void uuidIsStableForTheSameName() {
        OfflineSession first = new OfflineSession("Steve");
        OfflineSession second = new OfflineSession("Steve");
        assertEquals(first.uuid(), second.uuid());
    }

    @Test
    void uuidHasNoDashes() {
        assertFalse(new OfflineSession("Player_1").uuid().contains("-"));
    }

    @Test
    void differentNamesProduceDifferentUuids() {
        assertNotEquals(new OfflineSession("Alex").uuid(), new OfflineSession("Steve").uuid());
    }

    @Test
    void offlineSessionsUseLegacyUserTypeAndDummyToken() {
        OfflineSession session = new OfflineSession("Steve");
        assertEquals("legacy", session.userType());
        assertEquals("0", session.accessToken());
        assertFalse(session.needsAuthlibInjector());
    }

    @Test
    void nameIsTrimmed() {
        assertEquals("Steve", new OfflineSession("  Steve  ").username());
    }

    @Test
    void rejectsInvalidNicknames() {
        var authenticator = new OfflineAuthenticator();
        assertThrowsRejected(() -> authenticator.login(
                new LoginRequest(AuthProviderInfo.OFFLINE, Map.of("username", "way-too-long-name"), null),
                s -> { }));
        assertThrowsRejected(() -> authenticator.login(
                new LoginRequest(AuthProviderInfo.OFFLINE, Map.of("username", "bad name!"), null),
                s -> { }));
    }

    private static void assertThrowsRejected(ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("expected LoginRequest.Rejected");
        } catch (LoginRequest.Rejected expected) {
            // as intended
        } catch (Exception e) {
            throw new AssertionError("wrong exception: " + e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void acceptsValidNickname() throws Exception {
        var authenticator = new OfflineAuthenticator();
        AuthSession session = authenticator.login(
                new LoginRequest(AuthProviderInfo.OFFLINE, Map.of("username", "Lorikowka"), null),
                s -> { });
        assertEquals("Lorikowka", session.username());
    }

    }