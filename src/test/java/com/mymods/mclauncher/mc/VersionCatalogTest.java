package com.mymods.mclauncher.mc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class VersionCatalogTest {

    private static final String MANIFEST = """
            {"versions":[
                {"id":"1.20.1","type":"release",
                 "url":"https://example.invalid/1.20.1.json","releaseTime":"2023-06-12T13:25:51+00:00"},
                {"id":"24w14a","type":"snapshot",
                 "url":"https://example.invalid/24w14a.json","releaseTime":"2024-04-03T16:00:00+00:00"}
            ]}
            """;

    @TempDir
    Path dir;

    /** A copy younger than the freshness window replaces the network fetch entirely. */
    @Test
    void freshCacheReplacesTheNetworkFetch() throws Exception {
        Path cache = dir.resolve("version_manifest_v2.json");
        Files.writeString(cache, MANIFEST);
        Files.setLastModifiedTime(cache, FileTime.from(Instant.now().minusSeconds(30 * 60)));
        FileTime before = Files.getLastModifiedTime(cache);

        VersionCatalog catalog = new VersionCatalog(cache);
        catalog.refresh();

        assertNotNull(catalog.find("1.20.1"));
        assertNotNull(catalog.find("24w14a"));
        assertEquals(List.of("1.20.1"),
                catalog.releases().stream().map(McVersion::id).toList());
        // The network path rewrites the file; an untouched timestamp proves the fetch was skipped.
        assertEquals(before, Files.getLastModifiedTime(cache));
    }
}
