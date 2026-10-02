package com.mymods.mclauncher.auth;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * On-disk store for cached sessions, keyed by provider id. Ely and Microsoft tokens survive
 * restarts so the player does not re-enter credentials every launch; passwords are never stored.
 */
public final class TokenStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<String, CachedSession> sessions = new HashMap<>();

    public record CachedSession(String username, String uuid, String accessToken, String clientToken,
                               String refreshToken, String userType, String apiRoot, long expiresAt) {

        boolean isExpired() {
            return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
        }

        boolean hasRefreshToken() {
            return refreshToken != null && !refreshToken.isBlank();
        }
    }

    public TokenStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, CachedSession> loaded = GSON.fromJson(reader, new TypeToken<Map<String, CachedSession>>() {}.getType());
            if (loaded != null) {
                sessions.putAll(loaded);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Token store unreadable, starting empty: " + e.getMessage());
        }
    }

    public synchronized CachedSession get(String providerId) {
        return sessions.get(providerId);
    }

    public synchronized void put(String providerId, CachedSession session) {
        sessions.put(providerId, session);
        flush();
    }

    public synchronized void remove(String providerId) {
        sessions.remove(providerId);
        flush();
    }

    private void flush() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(sessions, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Failed to persist tokens: " + e.getMessage());
        }
    }
}