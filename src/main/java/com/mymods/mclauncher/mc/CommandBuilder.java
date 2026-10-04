package com.mymods.mclauncher.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mymods.mclauncher.auth.AuthSession;

/**
 * Builds the JVM and game command lines for a version.
 *
 * <p>Modern versions describe their own arguments in the version JSON: a {@code jvm} list and a
 * {@code game} list, each with rules deciding which OS/arch they apply to and which placeholder
 * each argument receives. Older versions (pre 1.13) have no arguments object at all, so their
 * arguments are assembled here from the fixed historical set.
 */
public final class CommandBuilder {

    /** Everything the templates can interpolate. */
    public record Context(Path gameDir, Path assetsDir, String assetsIndexId, Path versionDir,
                          Path librariesDir, Path nativesDir, AuthSession session, String versionId,
                          String versionType, String mainClass, int width, int height,
                          String clientId, List<String> extraJvmArgs, List<Library> libraries,
                          Path clientJar) {

        public Map<String, String> placeholders(VersionMetadata version) {
            return placeholders(version, "");
        }

        public Map<String, String> placeholders(VersionMetadata version,
                                                String quickPlayMultiplayer) {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("${auth_player_name}", session.username());
            map.put("${version_name}", versionId);
            map.put("${game_directory}", gameDir.toAbsolutePath().toString());
            map.put("${assets_root}", assetsDir.toAbsolutePath().toString());
            map.put("${assets_index_name}", assetsIndexId);
            map.put("${auth_uuid}", session.uuid());
            map.put("${auth_access_token}", session.accessToken());
            map.put("${clientid}", clientId);
            map.put("${auth_xuid}", session instanceof com.mymods.mclauncher.auth.MicrosoftSession ? "0" : "");
            map.put("${user_type}", session.userType());
            map.put("${version_type}", versionType);
            map.put("${resolution_width}", String.valueOf(width));
            map.put("${resolution_height}", String.valueOf(height));
            map.put("${library_directory}", librariesDir.toAbsolutePath().toString());
            map.put("${classpath_separator}", java.io.File.pathSeparator);
            map.put("${natives_directory}", nativesDir.toAbsolutePath().toString());
            map.put("${launcher_name}", "MCLauncher");
            map.put("${quickPlayPath}", "");
            map.put("${quickPlaySingleplayer}", "");
                map.put("${quickPlayMultiplayer}",
                    quickPlayMultiplayer == null ? "" : quickPlayMultiplayer);
            map.put("${quickPlayRealms}", "");
            // Kept in sync with `version` in build.gradle.kts by hand.
            map.put("${launcher_version}", "0.2.0");
            map.put("${classpath}", classpath(version).stream()
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .reduce((a, b) -> a + java.io.File.pathSeparator + b)
                    .orElse(""));
            return map;
        }

        /** Client jar first, then every applicable library, matching vanilla's ordering. */
        public List<Path> classpath(VersionMetadata version) {
            return classpath(version, true);
        }

        /**
         * @param includeClientJar false for FML-based loaders, which take the obfuscated jar through
         *                         {@code -DlegacyClassPath} instead of the classpath
         */
        public List<Path> classpath(VersionMetadata version, boolean includeClientJar) {
            List<Path> entries = new ArrayList<>();
            if (includeClientJar) {
                entries.add(clientJar());
            }
            for (Library library : libraries) {
                // LWJGL 3.3 finds its DLLs inside the -natives- jars on the classpath and unpacks
                // them itself, so those entries have to stay. The copies we pre-extract into
                // nativesDir are the fallback for older versions that use java.library.path.
                if (!library.appliesHere() || library.path() == null) {
                    continue;
                }
                entries.add(librariesDir.resolve(library.path().replace('\\', '/')));
            }
            return entries;
        }

        private String joinClasspath(List<Path> entries) {
            return entries.stream()
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .reduce((a, b) -> a + java.io.File.pathSeparator + b)
                    .orElse("");
        }

        /**
         * The jars an FML-based loader needs on its module layer but does not list as libraries.
         *
         * <p>Forge's generated version JSON leaves the loader's own modules out of {@code libraries}
         * on purpose and names them in {@code -DignoreList} instead. That only works if the launcher
         * puts them on {@code -DlegacyClassPath}: BootstrapLauncher turns every jar named there into
         * a module, and looks in the resulting layer for the service that hands control to
         * ModLauncher. Leave them out and the layer comes up without it and the bootstrap dies with
         * a bare {@code No value present} from {@code Optional.orElseThrow()}.
         *
         * <p>Discovery is driven by the loader's own ignore list rather than by hardcoded
         * coordinates, so it keeps working across Forge and NeoForge versions.
         */
        public List<Path> loaderModules(List<String> jvmArgs) {
            Set<String> wanted = new LinkedHashSet<>();
            for (String token : ignoreListTokens(jvmArgs)) {
                String artifact = token.endsWith("-") ? token.substring(0, token.length() - 1) : token;
                if (!artifact.isEmpty() && !artifact.contains("$") && !artifact.endsWith(".jar")) {
                    wanted.add(artifact);
                }
            }
            if (wanted.isEmpty() || !Files.isDirectory(librariesDir)) {
                return List.of();
            }

            // Maven layout is group directories, then the artifact, then the version, then the jar.
            Map<String, List<Path>> byArtifact = new LinkedHashMap<>();
            try (var paths = Files.walk(librariesDir, 4)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .forEach(p -> {
                            Path artifactDir = p.getParent() == null ? null : p.getParent().getParent();
                            if (artifactDir == null || artifactDir.getParent() == null) {
                                return;
                            }
                            String artifact = artifactDir.getFileName().toString();
                            if (wanted.contains(artifact)) {
                                byArtifact.computeIfAbsent(artifact, k -> new ArrayList<>()).add(p);
                            }
                        });
            } catch (IOException e) {
                return List.of();
            }

            List<Path> result = new ArrayList<>();
            for (List<Path> candidates : byArtifact.values()) {
                result.add(preferLoaderModule(candidates));
            }
            return result;
        }

        /**
         * Picks one jar out of an artifact that has several.
         *
         * <p>Forge publishes its module as both a {@code universal} and a {@code client} artifact,
         * and the layer can only hold one of them: both are the same module, so loading both makes
         * BootstrapLauncher fail on a duplicate key. {@code universal} is the one the loader expects.
         * Otherwise the newest version directory wins, which matters after an in-place upgrade.
         */
        private static Path preferLoaderModule(List<Path> candidates) {
            return candidates.stream()
                    .filter(p -> p.getFileName().toString().contains("-universal"))
                    .findFirst()
                    .orElseGet(() -> candidates.stream()
                            .max(Comparator.comparing(p -> p.getParent().getFileName().toString()))
                            .orElseThrow());
        }

        /** The artifact names from the loader's {@code -DignoreList}, empty when it declares none. */
        private static List<String> ignoreListTokens(List<String> jvmArgs) {
            String prefix = "-DignoreList=";
            for (String arg : jvmArgs) {
                if (arg.startsWith(prefix)) {
                    return List.of(arg.substring(prefix.length()).split(","));
                }
            }
            return List.of();
        }

        /**
         * Deliberately does not add a mod loader's patched "client extra" jar.
         *
         * <p>The Forge installer does produce {@code client-<mcp>-extra.jar}, and the loader's
         * arguments mention it in {@code -DignoreList}, which makes it look like a classpath entry.
         * Adding it is wrong: it declares the same packages as the inherited client jar, and the JVM
         * then refuses to build the module layer with a split package error
         * ({@code Module minecraft contains package net.minecraft.server, module _1._20._1 exports
         * package net.minecraft.server to minecraft}). The loader resolves it through the module
         * path on its own, so the classpath stays the inherited client jar plus the libraries.
         */

        /**
         * The jar that has to be on the classpath for the game's own classes.
         *
         * <p>A mod loader profile does not own a client jar: it inherits {@code downloads} from the
         * vanilla version it extends, so the base version's jar is the one to use. Falling back to
         * {@code <versionDir>/<versionId>.jar} covers versions that carry their own.
         */
        public Path clientJar() {
            return clientJar != null ? clientJar : versionDir.resolve(versionId + ".jar");
        }
    }

    private CommandBuilder() {}

    /** Full command: java + JVM args + main class + game args. */
    public static List<String> build(Path javaExecutable, VersionMetadata version,
                                     Context context, boolean hasAuthlibInjector,
                                     Path authlibJar) {
        return build(javaExecutable, version, context, hasAuthlibInjector, authlibJar, "");
    }

    /** Full command with an optional server address for Minecraft's quick-play feature. */
    public static List<String> build(Path javaExecutable, VersionMetadata version,
                                     Context context, boolean hasAuthlibInjector,
                                     Path authlibJar, String quickPlayMultiplayer) {

        Map<String, String> vars = context.placeholders(version, quickPlayMultiplayer);
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toAbsolutePath().toString());

        for (String arg : context.extraJvmArgs()) {
            if (!arg.isBlank()) {
                command.add(arg.trim());
            }
        }

        // LWJGL normally unpacks its natives into a temp dir; keep them in the version's own
        // directory instead so a rerun does not re-extract 14 DLLs every time. This must come
        // before the version's own jvm arguments, which already set java.library.path.
        command.add("-Dorg.lwjgl.system.SharedLibraryExtractPath="
                + context.nativesDir().toAbsolutePath());

        List<String> jvmArgs = resolve(version, "jvm", vars);
        if (hasAuthlibInjector) {
            // authlib-injector must come first on the agent list so it patches authlib before
            // the game touches it. Its argument is the session server (API root) URL.
            String sessionServer = context.session().sessionServerUrl();
            if (sessionServer == null || sessionServer.isBlank()) {
                throw new IllegalStateException(
                        "Authlib-injector needs the Ely API root, but the session has none");
            }
            command.add("-javaagent:" + authlibJar.toAbsolutePath() + "=" + sessionServer);
        }
        // Forge and NeoForge run the game as named modules, and the loader reads Mojang's
        // obfuscated jar from legacyClassPath rather than from the classpath. Leaving it on the
        // classpath as well makes two modules export the same packages and the JVM refuses the
        // layer outright: "Modules minecraft and _1._20._1 export package net.minecraft.client.main
        // to module forge". The jar cannot go on the module path either, because the loader derives
        // the module name from the file name and "1.20.1" is not a Java identifier, so the JVM
        // rejects it with "Unable to derive module descriptor".
        boolean fml = declaresModulePath(jvmArgs);
        // A loader profile inherits Vanilla's "-cp ${classpath}" from the version it inherits from, so
        // the resolved arguments still carry it even though the loader's own JSON never mentions a
        // classpath. Everything on it is redundant with legacyClassPath, and the game jar among those
        // entries is precisely what the comment below describes, so the pair is dropped here.
        command.addAll(fml ? withoutClasspath(jvmArgs) : jvmArgs);

        // Vanilla's JSON supplies "-cp ${classpath}" itself. Forge and NeoForge do not: they list a
        // module path with -p and leave the classpath to the launcher, so without this the game
        // would start with an empty classpath and fail to find its own classes.
        if (fml) {
            // Everything the loader has to see goes on the legacy classpath, and nothing goes on the
            // real one. BootstrapLauncher builds its module layer out of legacyClassPath, so a jar
            // left on -cp is invisible to it and the loader cannot find its own service. The game's
            // own jar stays out of both: it has no usable module name ("1.20.1" is not a Java
            // identifier, so the JVM rejects it), and the loader fetches the jars it needs from the
            // library directory itself.
            List<Path> layer = new ArrayList<>(context.classpath(version, false));
            Set<Path> seen = new LinkedHashSet<>();
            for (Path entry : layer) {
                seen.add(entry.toAbsolutePath().normalize());
            }
            for (Path module : context.loaderModules(jvmArgs)) {
                if (seen.add(module.toAbsolutePath().normalize())) {
                    layer.add(module);
                }
            }
            command.add("-DlegacyClassPath=" + context.joinClasspath(layer));
        } else if (!providesClasspath(jvmArgs)) {
            command.add("-cp");
            command.add(context.joinClasspath(context.classpath(version)));
        }

        command.add(version.mainClass);

        List<String> gameArgs = resolve(version, "game", vars);
        command.addAll(gameArgs);
        return command;
    }

    /**
     * Drops a classpath flag and its value from resolved JVM arguments.
     *
     * <p>Both the separate-value spellings and the single {@code --class-path=...} form are handled,
     * because a version JSON may use either.
     */
    private static List<String> withoutClasspath(List<String> jvmArgs) {
        List<String> kept = new ArrayList<>(jvmArgs.size());
        for (int i = 0; i < jvmArgs.size(); i++) {
            String arg = jvmArgs.get(i);
            if (arg.startsWith("--class-path=")) {
                continue;
            }
            if (arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path")) {
                if (i + 1 < jvmArgs.size()) {
                    i++;
                }
                continue;
            }
            kept.add(arg);
        }
        return kept;
    }

    /**
     * Whether a version is launched by an FML-based loader.
     *
     * <p>ModLauncher, which Forge and NeoForge use, runs the game as named modules, and the only
     * thing that gives it away is the module path the loader's own arguments declare. Vanilla
     * versions never have one.
     */
    private static boolean declaresModulePath(List<String> jvmArgs) {
        for (String arg : jvmArgs) {
            if (arg.equals("-p") || arg.equals("--module-path")) {
                return true;
            }
        }
        return false;
    }

    /** Whether the version's own JVM arguments already set a classpath. */
    private static boolean providesClasspath(List<String> jvmArgs) {
        for (String arg : jvmArgs) {
            if (arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path")) {
                return true;
            }
        }
        return false;
    }

    /** Reads {@code arguments.jvm} or {@code arguments.game} and applies rule filtering. */
    private static List<String> resolve(VersionMetadata version, String section,
                                        Map<String, String> vars) {

        if (version.arguments == null || !version.arguments.has(section)) {
            return section.equals("game")
                    ? legacyGameArguments(version, vars)
                    : legacyJvmArguments(vars);
        }

        List<String> result = new ArrayList<>();
        for (JsonElement element : version.arguments.getAsJsonArray(section)) {
            // Mojang normally wraps each argument as {"rules": [...], "value": "..."},
            // but a bare string is valid too and means "always pass this".
            if (element.isJsonPrimitive()) {
                result.add(substitute(element.getAsString(), vars));
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            if (!rulesAllow(item.getAsJsonArray("rules"))) {
                continue;
            }
            JsonElement value = item.get("value");
            if (value == null) {
                continue;
            }
            if (value.isJsonArray()) {
                // A few entries group several arguments under one rules block.
                for (JsonElement single : value.getAsJsonArray()) {
                    result.add(substitute(single.getAsString(), vars));
                }
            } else {
                result.add(substitute(value.getAsString(), vars));
            }
        }
        return result;
    }

    /** Applies Mojang's rule semantics: a matching disallow vetoes, else allow wins. */
    private static boolean rulesAllow(JsonArray rules) {
        if (rules == null) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            if (!featuresApply(rule) || !ruleMatches(rule.getAsJsonObject("os"))) {
                continue;
            }
            if ("disallow".equals(rule.get("action").getAsString())) {
                return false;
            }
            allowed = true;
        }
        return allowed;
    }

    private static boolean ruleMatches(JsonObject os) {
        if (os == null) {
            return true;
        }
        if (os.has("name") && !os.get("name").getAsString().equals(Library.currentOs())) {
            return false;
        }
        if (os.has("arch") && !os.get("arch").getAsString().equals(Library.currentArch())) {
            return false;
        }
        return true;
    }

    /**
     * Whether the launcher can satisfy a rule's {@code features} block.
     *
     * <p>Mojang uses these for account-conditional arguments such as {@code --demo}, which must
     * only be passed for an actual demo account. The launcher knows nothing about the account
     * beyond the session it was given, and no session here is a demo one, so every feature-gated
     * argument is reported as inapplicable.
     */
    private static boolean featuresApply(JsonObject rule) {
        return !rule.has("features");
    }

    private static String substitute(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    /** Fixed JVM arguments for versions released before the arguments object existed. */
    private static List<String> legacyJvmArguments(Map<String, String> vars) {
        return substituteAll(List.of(
                "-Djava.library.path=${natives_directory}",
                "-cp", "${classpath}"), vars);
    }

    /** The argument set Mojang shipped with 1.12 and earlier. */
    private static List<String> legacyGameArguments(VersionMetadata version, Map<String, String> vars) {
        List<String> args = new ArrayList<>(List.of(
                "--username", "${auth_player_name}",
                "--version", "${version_name}",
                "--gameDir", "${game_directory}",
                "--assetsDir", "${assets_root}",
                "--assetIndex", "${assets_index_name}",
                "--uuid", "${auth_uuid}",
                "--accessToken", "${auth_access_token}",
                "--userType", "${user_type}",
                "--versionType", "${version_type}",
                "--width", "${resolution_width}",
                "--height", "${resolution_height}"));
        return substituteAll(args, vars);
    }

    private static List<String> substituteAll(List<String> templates, Map<String, String> vars) {
        List<String> result = new ArrayList<>(templates.size());
        for (String template : templates) {
            result.add(substitute(template, vars));
        }
        return result;
    }

    /** Numeric comparison of dotted Minecraft versions, e.g. 1.20 > 1.19.4. */
    public static boolean compareAtLeast(String actual, String minimum) {
        String[] a = actual.split("\\.");
        String[] b = minimum.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int left = i < a.length ? parseInt(a[i]) : 0;
            int right = i < b.length ? parseInt(b[i]) : 0;
            if (left != right) {
                return left > right;
            }
        }
        return true;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}