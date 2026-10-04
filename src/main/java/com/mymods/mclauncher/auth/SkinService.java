package com.mymods.mclauncher.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mymods.mclauncher.util.Http;

/** Looks up the official skin texture for a Minecraft profile UUID. */
public final class SkinService {

    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{32}");

    private SkinService() {}

    public static String findTextureUrl(String uuid) throws IOException, InterruptedException {
        if (uuid == null || !UUID.matcher(uuid).matches()) {
            return null;
        }
        JsonObject profile = Http.getJson(
                "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid
                        + "?unsigned=false");
        return textureUrl(profile);
    }

    static String textureUrl(JsonObject profile) {
        try {
            for (var property : profile.getAsJsonArray("properties")) {
                JsonObject entry = property.getAsJsonObject();
                if (!"textures".equals(entry.get("name").getAsString())) {
                    continue;
                }
                String value = entry.get("value").getAsString();
                String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                JsonObject skin = JsonParser.parseString(decoded).getAsJsonObject()
                        .getAsJsonObject("textures").getAsJsonObject("SKIN");
                if (skin == null || !skin.has("url")) {
                    return null;
                }
                String url = skin.get("url").getAsString();
                if (url.startsWith("http://textures.minecraft.net/")) {
                    return "https://" + url.substring("http://".length());
                }
                return url.startsWith("https://textures.minecraft.net/") ? url : null;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }
}