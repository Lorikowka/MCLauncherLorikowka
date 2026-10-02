package com.mymods.mclauncher.auth;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Offline "login": the launcher only asks for a name. */
public final class OfflineAuthenticator implements Authenticator {

    public static final String FIELD_USERNAME = "username";

    private static final AuthProviderInfo INFO =
            new AuthProviderInfo(AuthProviderInfo.OFFLINE, "Offline (без входа)", false);

    @Override
    public AuthProviderInfo info() {
        return INFO;
    }

    @Override
    public List<String> requiredFields() {
        return List.of(FIELD_USERNAME);
    }

    @Override
    public AuthSession login(LoginRequest request, Consumer<String> onProgress)
            throws LoginRequest.Rejected {
        String name = request.fields().get(FIELD_USERNAME);
        if (name == null || name.isBlank()) {
            throw new LoginRequest.Rejected("Введите никнейм");
        }
        String trimmed = name.trim();
        if (!trimmed.matches("[A-Za-z0-9_]{1,16}")) {
            throw new LoginRequest.Rejected("Ник должен быть 1-16 символов, латиница, цифры и _");
        }
        onProgress.accept("Offline-режим: " + trimmed);
        return new OfflineSession(trimmed);
    }

    @Override
    public void signout(AuthSession session) {
        // Nothing is stored server side.
    }

    public static LoginRequest request(String username) {
        return new LoginRequest(AuthProviderInfo.OFFLINE, Map.of(FIELD_USERNAME, username), null);
    }

    /**
     * The same rule {@link #login} applies, so the UI can decide up front whether the typed name
     * is playable instead of failing after a click.
     */
    public static boolean isValidNickname(String name) {
        return name != null && name.trim().matches("[A-Za-z0-9_]{1,16}");
    }
}