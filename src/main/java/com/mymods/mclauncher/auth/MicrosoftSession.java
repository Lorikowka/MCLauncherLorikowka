package com.mymods.mclauncher.auth;

/** Premium Microsoft session. Tokens come straight from Minecraft Services, so no agent is needed. */
public record MicrosoftSession(String username, String uuid, String accessToken, String refreshToken)
        implements AuthSession {

    @Override
    public String userType() {
        return "mojang";
    }

    @Override
    public boolean needsAuthlibInjector() {
        return false;
    }
}