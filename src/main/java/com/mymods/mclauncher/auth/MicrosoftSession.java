package com.mymods.mclauncher.auth;

/** Premium Microsoft session. Tokens come straight from Minecraft Services, so no agent is needed. */
public record MicrosoftSession(String username, String uuid, String accessToken, String refreshToken)
        implements AuthSession {

    /** Azure app id of the official launcher, passed to the game as {@code --clientId}. */
    public static final String LAUNCHER_CLIENT_ID = "00000000402b5328";

    @Override
    public String userType() {
        return "mojang";
    }

    @Override
    public boolean needsAuthlibInjector() {
        return false;
    }
}