package com.mymods.mclauncher.mc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaRuntimesTest {

    @TempDir
    Path tempDir;

    @Test
    void scanFindsTheRuntimeRunningTheLauncher() {
        Path currentHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();

        assertTrue(JavaRuntimes.scan(tempDir.resolve("runtimes")).stream()
                .anyMatch(runtime -> runtime.home().toAbsolutePath().normalize().equals(currentHome)));
    }
}