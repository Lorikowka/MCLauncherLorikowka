package com.mymods.mclauncher.mc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds a Java runtime suitable for a Minecraft version. */
public final class JavaRuntimes {

    /** Points at a JDK/JRE installation. */
    public record Runtime(Path home, int majorVersion) {
        public Path java() {
            return home.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        }

        public boolean exists() {
            return Files.isRegularFile(java());
        }
    }

    private static final Pattern VERSION_LINE =
            Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    private JavaRuntimes() {}

    /**
     * Resolves the Java to run Minecraft with.
     *
     * <p>Order: explicit setting, then launcher-managed runtimes, then the system runtime.
     * A managed runtime matching {@code requiredMajor} wins over the system one so an old Java
     * does not break a newer Minecraft.
     */
    public static Runtime resolve(LauncherSettings settings, int requiredMajor, Consumer<String> log)
            throws IOException {

        String configured = settings.javaPath();
        if (configured != null && !configured.isBlank()) {
            Path java = Path.of(configured);
            if (Files.isRegularFile(java)) {
                Runtime detected = detect(java);
                if (detected != null) {
                    return detected;
                }
            }
            log.accept("Настроенный Java не найден или не запускается: " + configured);
        }

        List<Runtime> managed = listManaged(settings.runtimesDir());
        for (Runtime runtime : managed) {
            if (requiredMajor <= 0 || runtime.majorVersion() == requiredMajor) {
                log.accept("Используем загруженный Java " + runtime.majorVersion());
                return runtime;
            }
        }

        Runtime system = detectSystem();
        if (system == null) {
            throw new IOException("Java не найдена. Укажите путь в настройках лаунчера.");
        }

        if (requiredMajor > 0 && system.majorVersion() < requiredMajor) {
            Runtime best = managed.stream()
                    .filter(r -> r.majorVersion() >= requiredMajor)
                    .min((a, b) -> Integer.compare(a.majorVersion(), b.majorVersion()))
                    .orElse(null);
            if (best != null) {
                log.accept("Системная Java " + system.majorVersion() + " старее нужной "
                        + requiredMajor + ", берём загруженную " + best.majorVersion());
                return best;
            }
            throw new IOException("Minecraft " + requiredMajor + " требует Java "
                    + requiredMajor + " или новее, а доступна только " + system.majorVersion()
                    + ". Загрузите runtime в настройках лаунчера.");
        }

        log.accept("Используем системную Java " + system.majorVersion());
        return system;
    }

    /** Launcher-managed runtimes live in {@code runtimes/jdk-<major>}. */
    public static List<Runtime> listManaged(Path runtimesDir) throws IOException {
        List<Runtime> result = new ArrayList<>();
        if (!Files.isDirectory(runtimesDir)) {
            return result;
        }
        try (var stream = Files.list(runtimesDir)) {
            for (Path dir : stream.toList()) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                Runtime detected = detect(dir.resolve("bin").resolve(isWindows() ? "java.exe" : "java"));
                if (detected != null) {
                    result.add(detected);
                }
            }
        }
        return result;
    }

    public static Runtime detectSystem() {
        String home = System.getProperty("java.home");
        if (home == null) {
            return null;
        }
        return detect(Path.of(home).resolve("bin").resolve(isWindows() ? "java.exe" : "java"));
    }

    /** Runs {@code java -version} and parses the major version out of the first line. */
    public static Runtime detect(Path javaExecutable) {
        if (!Files.isRegularFile(javaExecutable)) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(javaExecutable.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            int major = parseMajor(output);
            if (major <= 0) {
                return null;
            }
            Path home = javaExecutable.getParent() == null ? null : javaExecutable.getParent().getParent();
            return home == null ? null : new Runtime(home, major);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static int parseMajor(String output) {
        Matcher matcher = VERSION_LINE.matcher(output);
        if (!matcher.find()) {
            return 0;
        }
        String major = matcher.group(1);
        String minor = matcher.group(2);
        // Java 8 and earlier report only "1.8.0_xxx".
        if ("1".equals(major)) {
            return minor == null ? 1 : Integer.parseInt(minor);
        }
        return Integer.parseInt(major);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    }

    /** Mirrors {@link JavaRuntimes} inputs without pulling in the UI settings class. */
    public record LauncherSettings(String javaPath, Path runtimesDir) {}
}