package com.mymods.mclauncher.mc;

import java.util.ArrayList;
import java.util.List;

/** Single entry of Mojang's version manifest. */
public record McVersion(String id, String type, String url, String releaseTime) {

    public static List<McVersion> fromJson(com.google.gson.JsonObject manifest) {
        List<McVersion> versions = new ArrayList<>();
        for (var element : manifest.getAsJsonArray("versions")) {
            var o = element.getAsJsonObject();
            versions.add(new McVersion(
                    o.get("id").getAsString(),
                    o.get("type").getAsString(),
                    o.get("url").getAsString(),
                    o.get("releaseTime").getAsString()));
        }
        return versions;
    }

    /** True when the entry is a stable release rather than a snapshot or old beta. */
    public boolean isRelease() {
        return "release".equals(type);
    }
}