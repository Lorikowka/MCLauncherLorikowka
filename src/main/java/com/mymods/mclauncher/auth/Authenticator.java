package com.mymods.mclauncher.auth;

import java.util.function.Consumer;

import com.mymods.mclauncher.config.LauncherConfig;

/**
 * Turns user input into an {@link AuthSession}.
 *
 * <p>Every implementation is stateless: credentials live in the returned session only, and any
 * long-lived token is persisted by the caller via {@link TokenStore}.
 */
public interface Authenticator {

    AuthProviderInfo info();

    /**
     * @param request values collected by the UI, keyed by the names in {@link #requiredFields()}
     * @param onProgress receives human-readable status lines for the UI (device codes, code URLs)
     */
    AuthSession login(LoginRequest request, Consumer<String> onProgress)
            throws LoginRequest.NeedsMoreInput, LoginRequest.Rejected;

    /** Field names the login form should render, in order. */
    default java.util.List<String> requiredFields() {
        return java.util.List.of();
    }

    /** Lets the UI validate {@link LauncherConfig} before attempting a login. */
    default String validateConfig(LauncherConfig config) {
        return null;
    }

    /** Invalidate the current session server side, where the provider supports it. */
    default void signout(AuthSession session) throws Exception {
        // Offline sessions have nothing to sign out of.
    }
}