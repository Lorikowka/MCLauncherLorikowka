package com.mymods.mclauncher.auth;

/**
 * Service description shown in the login UI.
 *
 * @param id persisted value written to settings.json
 * @param displayName label shown in the account tab
 * @param needsCredentials whether the user has to type anything at all
 */
public record AuthProviderInfo(String id, String displayName, boolean needsCredentials) {

    public static final String OFFLINE = "offline";
    public static final String ELY = "ely";
    public static final String MICROSOFT = "microsoft";

    public static AuthProviderInfo[] all() {
        return new AuthProviderInfo[] {
                new AuthProviderInfo(OFFLINE, "Offline (без входа)", false),
                new AuthProviderInfo(ELY, "Ely.by", true),
                new AuthProviderInfo(MICROSOFT, "Microsoft (Xbox)", true),
        };
    }
}