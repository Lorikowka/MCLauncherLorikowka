package com.mymods.mclauncher.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class SkinServiceTest {

    @Test
    void readsTextureUrlFromTheSessionProfileProperty() {
        String skinData = "{\"textures\":{\"SKIN\":{\"url\":"
                + "\"http://textures.minecraft.net/texture/example\"}}}";
        JsonObject property = new JsonObject();
        property.addProperty("name", "textures");
        property.addProperty("value", Base64.getEncoder().encodeToString(
                skinData.getBytes(StandardCharsets.UTF_8)));
        JsonArray properties = new JsonArray();
        properties.add(property);
        JsonObject profile = new JsonObject();
        profile.add("properties", properties);

        assertEquals("https://textures.minecraft.net/texture/example",
                SkinService.textureUrl(profile));
    }

    @Test
    void ignoresProfilesWithoutAValidTextureProperty() {
        assertNull(SkinService.textureUrl(new JsonObject()));
    }
}