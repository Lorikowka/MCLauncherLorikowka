package com.mymods.mclauncher.auth;

/**
 * Ely session. Ely tokens are real JWTs with the {@code minecraft_server_session} claim, so the
 * game can authenticate against Ely servers. authlib-injector redirects the session server calls
 * to Ely; without it the client would still talk to Mojang and skins would be lost.
 */
public record ElySession(String username, String uuid, String accessToken, String clientToken, String apiRoot)
        implements AuthSession {

    @Override
    public String userType() {
        return "mojang";
    }

    @Override
    public boolean needsAuthlibInjector() {
        return true;
    }

    @Override
    public String sessionServerUrl() {
        return apiRoot;
    }
}