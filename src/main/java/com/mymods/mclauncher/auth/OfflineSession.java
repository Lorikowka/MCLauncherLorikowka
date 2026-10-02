package com.mymods.mclauncher.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Offline session. The UUID is derived from the name with the same MD5 scheme Mojang uses
 * for offline servers, so worlds, inventories and skins stay attached to the player.
 */
public record OfflineSession(String username) implements AuthSession {

    private static final String DUMMY_TOKEN = "0";

    public OfflineSession {
        username = username.trim();
    }

    @Override
    public String uuid() {
        return offlineUuid().toString().replace("-", "");
    }

    @Override
    public String accessToken() {
        return DUMMY_TOKEN;
    }

    @Override
    public String userType() {
        return "legacy";
    }

    public UUID offlineUuid() {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }
}