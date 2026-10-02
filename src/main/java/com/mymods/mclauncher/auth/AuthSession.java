package com.mymods.mclauncher.auth;

/** A resolved session that can be turned into the placeholders Minecraft expects. */
public interface AuthSession {

    /** Player name without the leading {@code @}. */
    String username();

    /** UUID without dashes, exactly as the game passes it on the command line. */
    String uuid();

    /** Token handed to the game. Ely/Microsoft tokens are real, offline uses a dummy. */
    String accessToken();

    /** {@code mojang} for premium sessions, {@code legacy} for offline ones. */
    String userType();

    /** Whether this session needs authlib-injector to redirect session servers. */
    default boolean needsAuthlibInjector() {
        return false;
    }

    /** Root of the session/profile API for third-party auth. Empty means Mojang defaults. */
    default String sessionServerUrl() {
        return "";
    }

    /** Lets the launcher renew a session in the background, e.g. a stale Ely token. */
    interface Refreshing extends AuthSession {
        AuthSession refresh() throws Exception;
    }
}