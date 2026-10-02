package com.mymods.mclauncher.auth;

import java.util.function.Consumer;

import com.mymods.mclauncher.config.LauncherConfig;

/** Builds the {@link Authenticator} for the provider chosen in settings. */
public final class AuthProviders {

    private AuthProviders() {}

    public static Authenticator create(LauncherConfig config, TokenStore tokens, String providerId) {
        String id = providerId == null || providerId.isBlank()
                ? AuthProviderInfo.OFFLINE
                : providerId.trim();
        return switch (id) {
            case AuthProviderInfo.ELY -> new ElyAuthenticator(config, tokens);
            case AuthProviderInfo.MICROSOFT -> new MicrosoftAuthenticator(config, tokens);
            default -> new OfflineAuthenticator();
        };
    }

    /**
     * Tries to obtain a session without user interaction: cached Ely/Microsoft tokens are renewed
     * where possible. Returns null when the user has to go through the login form.
     */
    public static AuthSession restoreSilent(LauncherConfig config, TokenStore tokens,
                                            String providerId, Consumer<String> onProgress) {
        try {
            return switch (providerId == null ? "" : providerId) {
                case AuthProviderInfo.ELY -> new ElyAuthenticator(config, tokens).restore(onProgress);
                case AuthProviderInfo.MICROSOFT ->
                        new MicrosoftAuthenticator(config, tokens).restore(onProgress);
                default -> null;
            };
        } catch (Exception e) {
            onProgress.accept("Автовход не удался: " + e.getMessage());
            return null;
        }
    }
}