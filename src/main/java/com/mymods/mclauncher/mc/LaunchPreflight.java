package com.mymods.mclauncher.mc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Checks that every file a launch will read is already on disk, before the game is started.
 *
 * <p>Two different gaps reach the user as the same unhelpful crash if they are not caught here.
 * A library the downloader skipped is invisible until the JVM fails to find a class; worse, the
 * Forge installer writes its version JSON <em>before</em> it downloads anything, so a profile can
 * exist while the jars FML resolves at launch were never produced. FML then derives those paths
 * from the {@code --fml} arguments itself and dies inside its own locator with
 * {@code Invalid paths argument, contained no existing paths} — a stack trace the user cannot act
 * on. The same paths are reconstructed here from the same arguments, so an incomplete install is
 * reported in the launcher's own words, and the controller gets a chance to repair it by running
 * the installer again.
 */
public final class LaunchPreflight {

    /**
     * The only loader layout verified here.
     *
     * <p>Forge's on-disk layout is reconstructed from paths that were checked against real
     * installs of 1.16.5, 1.19.2 and 1.20.1. NeoForge keeps the same argument names but writes a
     * different layout that has no verified sample, and guessing it here could block a launch that
     * actually works; so for NeoForge, Fabric and vanilla FML stays the one to report.
     */
    private static final String FORGE_GROUP = "net.minecraftforge";

    private LaunchPreflight() {}

    /**
     * Files named by the final launch command that do not exist, loader jars included.
     *
     * <p>Classpath entries, the FML module path, the legacy classpath and the java agent are all
     * read by the JVM or the loader before anything can explain a failure, so each path they name
     * is checked here. The loader's own jars are derived from the {@code --fml} arguments carried
     * in the same command, because they are neither on the classpath nor in the version JSON's
     * library list.
     */
    public static List<Path> missing(List<String> command, Path librariesDir) {
        Set<Path> missing = new LinkedHashSet<>();
        for (String file : fileReferences(command)) {
            Path path;
            try {
                path = Path.of(file);
            } catch (InvalidPathException unparsable) {
                // A token that is not a path at all cannot be a missing file either; the JVM
                // reports whatever is wrong with it when the command actually runs.
                continue;
            }
            if (!Files.isRegularFile(path)) {
                missing.add(path);
            }
        }
        missing.addAll(missingLoaderArtifacts(fmlArgs(command), librariesDir));
        return List.copyOf(missing);
    }

    /**
     * The loader jars a version JSON's own launch arguments demand but that are absent.
     *
     * <p>Used by the controller before the game pipeline runs, so an install the downloader would
     * happily launch is repaired first: the downloader has no URLs for these jars, only the
     * installer produces them.
     */
    public static List<Path> missingLoaderArtifacts(JsonObject versionJson, Path librariesDir) {
        return missingLoaderArtifacts(fmlArgs(gameArgTokens(versionJson)), librariesDir);
    }

    /** One line per missing file, plus what to do about it. */
    public static String describe(List<Path> missing) {
        StringBuilder text = new StringBuilder(
                "Не хватает файлов для запуска — установка не докончена:");
        for (Path path : missing) {
            text.append("\n  ").append(path);
        }
        text.append("\nПереустанови версию или загрузчик — недостающее докачается.");
        return text.toString();
    }

    /**
     * The values of every file-carrying flag in the command: classpath, module path, legacy
     * classpath and the java agent.
     *
     * <p>Both spellings of the classpath flag are handled, joined lists are split on the platform
     * separator, and {@code -javaagent}'s trailing agent options are dropped: only the jar itself
     * has to exist. Game arguments are scanned too, but none of them can collide with a JVM flag,
     * so no section boundaries are needed.
     */
    private static List<String> fileReferences(List<String> command) {
        List<String> files = new ArrayList<>();
        for (int i = 0; i < command.size(); i++) {
            String token = command.get(i);
            switch (token) {
                case "-cp", "-classpath", "--class-path", "-p", "--module-path" -> {
                    if (i + 1 < command.size()) {
                        addSplitPaths(command.get(i + 1), files);
                    }
                }
                default -> {
                    if (token.startsWith("--class-path=")) {
                        addSplitPaths(token.substring("--class-path=".length()), files);
                    } else if (token.startsWith("--module-path=")) {
                        addSplitPaths(token.substring("--module-path=".length()), files);
                    } else if (token.startsWith("-DlegacyClassPath=")) {
                        addSplitPaths(token.substring("-DlegacyClassPath=".length()), files);
                    } else if (token.startsWith("-javaagent:")) {
                        String spec = token.substring("-javaagent:".length());
                        int options = spec.indexOf('=');
                        files.add(options < 0 ? spec : spec.substring(0, options));
                    }
                }
            }
        }
        return files;
    }

    private static void addSplitPaths(String joined, List<String> target) {
        if (joined == null || joined.isBlank()) {
            return;
        }
        for (String part : joined.split(Pattern.quote(File.pathSeparator))) {
            if (!part.isBlank()) {
                target.add(part);
            }
        }
    }

    /** The jars FML resolves at launch but that no other step checks. */
    private static List<Path> missingLoaderArtifacts(Map<String, String> fml, Path librariesDir) {
        String mc = fml.get("--fml.mcVersion");
        String forgeVersion = fml.get("--fml.forgeVersion");
        String group = fml.get("--fml.forgeGroup");
        if (mc == null || forgeVersion == null || !FORGE_GROUP.equals(group)) {
            return List.of();
        }

        List<Path> missing = new ArrayList<>();
        String mcp = fml.get("--fml.mcpVersion");
        if (mcp != null && !mcp.isBlank()) {
            // The obfuscated client is republished under maven coordinates built from these two
            // arguments, one jar for the merged classes and one for the patches.
            String mcMcp = mc + "-" + mcp;
            Path dir = librariesDir.resolve("net").resolve("minecraft")
                    .resolve("client").resolve(mcMcp);
            addWhenMissing(missing, dir.resolve("client-" + mcMcp + "-srg.jar"));
            addWhenMissing(missing, dir.resolve("client-" + mcMcp + "-extra.jar"));
        }

        // FML accepts any of the published forge artifacts for this version directory, so any
        // one of them counts as installed: the installer has shipped all three shapes over time.
        String mcForge = mc + "-" + forgeVersion;
        Path forgeDir = librariesDir.resolve("net").resolve("minecraftforge")
                .resolve("forge").resolve(mcForge);
        if (!Files.isRegularFile(forgeDir.resolve("forge-" + mcForge + "-client.jar"))
                && !Files.isRegularFile(forgeDir.resolve("forge-" + mcForge + "-universal.jar"))
                && !Files.isRegularFile(forgeDir.resolve("forge-" + mcForge + ".jar"))) {
            missing.add(forgeDir.resolve("forge-" + mcForge + "-client.jar"));
        }
        return missing;
    }

    private static void addWhenMissing(List<Path> missing, Path path) {
        if (!Files.isRegularFile(path)) {
            missing.add(path);
        }
    }

    /**
     * The game arguments of a version JSON as a flat token list, before substitution.
     *
     * <p>The {@code --fml} arguments the installer writes are plain adjacent strings, but Mojang's
     * general form is a mix of bare strings and objects wrapping a value or a value array, so all
     * three shapes are unwrapped here. Values keep their {@code ${...}} placeholders: the loader's
     * own arguments never use them.
     */
    static List<String> gameArgTokens(JsonObject versionJson) {
        List<String> tokens = new ArrayList<>();
        if (!versionJson.has("arguments") || !versionJson.get("arguments").isJsonObject()) {
            return tokens;
        }
        JsonObject arguments = versionJson.getAsJsonObject("arguments");
        if (!arguments.has("game") || !arguments.get("game").isJsonArray()) {
            return tokens;
        }
        JsonArray game = arguments.getAsJsonArray("game");
        for (JsonElement element : game) {
            if (element.isJsonPrimitive()) {
                tokens.add(element.getAsString());
            } else if (element.isJsonObject()) {
                addValueTokens(element.getAsJsonObject().get("value"), tokens);
            }
        }
        return tokens;
    }

    private static void addValueTokens(JsonElement value, List<String> tokens) {
        if (value == null) {
            return;
        }
        if (value.isJsonPrimitive()) {
            tokens.add(value.getAsString());
        } else if (value.isJsonArray()) {
            for (JsonElement single : value.getAsJsonArray()) {
                if (single.isJsonPrimitive()) {
                    tokens.add(single.getAsString());
                }
            }
        }
    }

    /**
     * The {@code --fml.*} arguments paired with their values.
     *
     * <p>Both the built command and a version JSON store them as adjacent tokens — flag, then
     * value — so one extraction serves both inputs.
     */
    static Map<String, String> fmlArgs(List<String> tokens) {
        Map<String, String> args = new LinkedHashMap<>();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("--fml.")) {
                args.put(token, tokens.get(i + 1));
            }
        }
        return args;
    }

    /** One {@code --fml} argument from a version JSON, or null when it declares none. */
    static String fmlArgument(JsonObject versionJson, String name) {
        return fmlArgs(gameArgTokens(versionJson)).get(name);
    }
}
