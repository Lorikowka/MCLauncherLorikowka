package com.mymods.mclauncher.mc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mymods.mclauncher.util.Http;

/**
 * Looks up which loader builds exist for a Minecraft version, and where their installer is.
 *
 * <p>Fabric publishes a meta API, Forge publishes a promotions file mapping each Minecraft version
 * to a build number, and NeoForge publishes a plain list of released versions. Each of those is a
 * different shape, so they are normalised here into a single "version id" that the rest of the
 * launcher can use without caring which loader it came from.
 */
public final class ModLoaderCatalog {

    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final String FORGE_PROMOTIONS =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/";
    private static final String NEOFORGE_VERSIONS =
            "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";
    private static final String NEOFORGE_MAVEN =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/";

    /**
     * Which NeoForge release line belongs to which Minecraft version.
     *
     * <p>NeoForge numbers its artifacts after the Minecraft version they target, but not by a plain
     * transformation: 1.20.1 is served by the 20.4 line while 1.20.2 is served by 20.2, and 1.20.4
     * is served by 20.4 as well. There is no published machine-readable index that maps Minecraft
     * versions to lines, so the supported ones are listed here and anything else is refused with a
     * clear message instead of guessing an installer that would build the wrong game.
     */
    private ModLoaderCatalog() {}

    /**
     * The NeoForge release-line prefix that serves a Minecraft version.
     *
     * <p>NeoForge names its builds after the Minecraft version they target with the leading {@code 1}
     * dropped, so {@code 1.20.4} is served by {@code 20.4.x} and {@code 1.21.1} by {@code 21.1.x}. A
     * bare two-part version like {@code 1.21} has no patch component to copy and was released as
     * {@code 21.0.x}. Deriving the prefix beats keeping a hand-written table: the table has to be
     * edited for every new Minecraft release or the launcher reports the version as unsupported, and
     * it goes stale silently in the meantime.
     *
     * <p>A version with no matching line is not an error. NeoForge took over from Forge at
     * {@code 1.20.2}, so {@code 1.20.1} derives {@code 20.1.} and finds nothing, which is the
     * correct answer; and a Minecraft release newer than anything NeoForge has shipped yet simply
     * has no builds either. Callers report that as "no builds published" rather than pretending the
     * mapping is broken.
     */
    static String neoForgeLine(String baseVersion) {
        Matcher matcher = NEOFORGE_LINE.matcher(baseVersion);
        if (matcher.matches()) {
            return matcher.group(1) + "." + matcher.group(2) + ".";
        }
        if (baseVersion.startsWith("1.")) {
            String rest = baseVersion.substring(2);
            // A two-part version such as 1.21 has no patch component to copy, and its line was
            // released as the .0 one. Without this the prefix would be "21.", which matches nothing.
            return rest.indexOf('.') >= 0 ? rest + "." : rest + ".0.";
        }
        return baseVersion;
    }

    /**
     * The newest loader build for a Minecraft version, as the loader itself spells it.
     *
     * @throws IOException when the loader publishes nothing for that Minecraft version
     */
    public static String latestBuild(ModLoaderType loader, String baseVersion)
            throws IOException, InterruptedException {

        return switch (loader) {
            case FABRIC -> latestStableFabric();
            case FORGE -> latestForgeBuild(baseVersion);
            case NEOFORGE -> latestNeoForgeBuild(baseVersion);
            case VANILLA -> throw new IOException("У ванильной версии нет мод-лоадера");
        };
    }

    /** Every loader build offered for a Minecraft version, newest first. */
    public static List<String> builds(ModLoaderType loader, String baseVersion)
            throws IOException, InterruptedException {

        return switch (loader) {
        case FABRIC -> fabricLoadersFor(baseVersion);
        case FORGE -> forgeBuildsFor(baseVersion);
        case NEOFORGE -> neoForgeBuildsFor(baseVersion);
        case VANILLA -> List.of();
        };
    }

    /**
     * Every stable Forge build published for a Minecraft version, newest first.
     *
     * <p>The promotions file only names the recommended and latest builds, which is the right
     * answer for "install Forge" and the wrong one for a user whose pack asks for a specific build.
     * Forge's maven metadata lists everything, keyed by {@code <mc>-<build>}, so the Minecraft
     * version is matched by prefix.
     *
     * <p>The build part has to be digits and dots. {@link #isStable} cannot be reused here: every
     * Forge version contains the dash that separates it from the Minecraft version, so it would
     * reject all of them. What marks a Forge pre-release is a suffix after the build, such as the
     * trailing {@code -1.7.10} that older Forge versions carry.
     */
    private static List<String> forgeBuildsFor(String baseVersion) throws IOException,
            InterruptedException {

        String prefix = baseVersion + "-";
        String metadata = Http.getText(FORGE_MAVEN + "forge/maven-metadata.xml");
        List<String> found = new ArrayList<>();
        Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(metadata);
        while (matcher.find()) {
            String version = matcher.group(1).trim();
            if (!version.startsWith(prefix)) {
                continue;
            }
            String build = version.substring(prefix.length());
            if (PLAIN_BUILD.matcher(build).matches()) {
                found.add(build);
            }
        }
        found.sort((a, b) -> compareVersions(b, a));
        return found;
    }

    /** Every stable NeoForge build on the line that serves a Minecraft version, newest first. */
    private static List<String> neoForgeBuildsFor(String baseVersion) throws IOException,
            InterruptedException {

        String line = neoForgeLine(baseVersion);
        JsonObject body = Http.getJson(NEOFORGE_VERSIONS);
        if (!body.has("versions")) {
            throw new IOException("NeoForge API неожиданно без списка versions");
        }
        List<String> found = new ArrayList<>();
        for (JsonElement element : body.getAsJsonArray("versions")) {
            String candidate = element.getAsString();
            if (candidate.startsWith(line) && isStable(candidate)) {
                found.add(candidate);
            }
        }
        found.sort((a, b) -> compareVersions(b, a));
        return found;
    }

    /**
     * The version id the installer writes for a profile.
     *
     * <p>Forge and NeoForge name their versions {@code <mc>-<loader>-<build>} and the installer
     * picks that name itself, so the launcher has to agree with it or the profile it records will
     * not be the one on disk. Fabric's id is launcher-chosen.
     */
    public static String profileId(ModLoaderType loader, String baseVersion, String loaderVersion) {
        if (loader == ModLoaderType.FABRIC) {
            return loaderVersion == null || loaderVersion.isBlank()
                    ? "fabric-" + baseVersion
                    : "fabric-" + baseVersion + "-" + loaderVersion;
        }
        String prefix = baseVersion + "-" + loader.id();
        return loaderVersion == null || loaderVersion.isBlank()
                ? prefix
                : prefix + "-" + loaderVersion;
    }

    /** Where the installer jar for a profile lives. */
    public static String installerUrl(ModLoaderType loader, String baseVersion, String loaderVersion)
            throws IOException {

        return switch (loader) {
            case FORGE -> FORGE_MAVEN + "forge/" + baseVersion + "-" + loaderVersion
                    + "/forge-" + baseVersion + "-" + loaderVersion + "-installer.jar";
            case NEOFORGE -> NEOFORGE_MAVEN + loaderVersion + "/neoforge-" + loaderVersion
                    + "-installer.jar";
            default -> throw new IOException(loader.displayName() + " не требует установщика");
        };
    }

    /**
     * Forge build for a Minecraft version.
     *
     * <p>The promotions file is keyed by {@code <mc>-latest} and {@code <mc>-recommended}, both
     * mapping to a bare build number, and a game with only one of the two still resolves.
     */
    private static String latestForgeBuild(String baseVersion) throws IOException,
            InterruptedException {

        JsonObject promos = Http.getJson(FORGE_PROMOTIONS);
        if (!promos.has("promos")) {
            throw new IOException("Forge promotions неожиданно без раздела promos");
        }
        JsonObject promos2 = promos.getAsJsonObject("promos");
        for (String key : new String[] {baseVersion + "-latest", baseVersion + "-recommended"}) {
            if (promos2.has(key) && promos2.get(key).isJsonPrimitive()) {
                return promos2.get(key).getAsString();
            }
        }
        throw new IOException("Forge не публикует сборку для " + baseVersion);
    }

    /** Newest stable NeoForge release on the line that serves a Minecraft version. */
    private static String latestNeoForgeBuild(String baseVersion) throws IOException,
            InterruptedException {

        String line = neoForgeLine(baseVersion);
        JsonObject body = Http.getJson(NEOFORGE_VERSIONS);
        if (!body.has("versions")) {
            throw new IOException("NeoForge API неожиданно без списка versions");
        }
        String newest = null;
        for (JsonElement element : body.getAsJsonArray("versions")) {
            String candidate = element.getAsString();
            if (!candidate.startsWith(line) || !isStable(candidate)) {
                continue;
            }
            if (newest == null || compareVersions(candidate, newest) > 0) {
                newest = candidate;
            }
        }
        if (newest == null) {
            throw new IOException("NeoForge не выпустил стабильных сборок для " + baseVersion);
        }
        return newest;
    }

    /** Stable releases only: betas and other pre-release suffixes are not install candidates. */
    private static boolean isStable(String version) {
        return !version.contains("-");
    }

    /** A Forge build number, which carries no pre-release suffix. */
    private static final Pattern PLAIN_BUILD = Pattern.compile("\\d+(\\.\\d+)*");

    /**
     * A Minecraft version split into the two components that make up a NeoForge line: {@code 1.20.4}
     * captures {@code 20} and {@code 4}, which {@link #neoForgeLine} turns into the prefix
     * {@code 20.4.}. Only versions of that exact shape match; anything else falls back to the
     * stripped form so an unrecognised version still yields a plausible prefix to probe.
     */
    private static final Pattern NEOFORGE_LINE = Pattern.compile("1\\.(\\d+)\\.(\\d+)");

    /** All Fabric loaders that support a Minecraft version, newest first. */
    private static List<String> fabricLoadersFor(String baseVersion) throws IOException,
            InterruptedException {

        JsonArray entries = Http.getJsonArray(FABRIC_META + "/versions/loader/" + baseVersion);
        List<String> stable = new ArrayList<>();
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.has("stable") && entry.get("stable").getAsBoolean()) {
                stable.add(entry.get("version").getAsString());
            }
        }
        return stable;
    }

    private static String latestStableFabric() throws IOException, InterruptedException {
        JsonArray versions = Http.getJsonArray(FABRIC_META + "/versions/loader");
        for (JsonElement element : versions) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.has("stable") && entry.get("stable").getAsBoolean()) {
                return entry.get("version").getAsString();
            }
        }
        throw new IOException("Не удалось определить последнюю стабильную версию Fabric");
    }

    /**
     * Numeric comparison of dotted versions.
     *
     * <p>Segment-wise numeric compare, so 20.4.9 sorts below 20.4.10 instead of above it as a plain
     * string compare would.
     */
    static int compareVersions(String a, String b) {
        String[] left = a.split("[.\\-]");
        String[] right = b.split("[.\\-]");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int l = i < left.length ? numeric(left[i]) : 0;
            int r = i < right.length ? numeric(right[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static int numeric(String segment) {
        StringBuilder digits = new StringBuilder();
        for (char c : segment.toCharArray()) {
            if (!Character.isDigit(c)) {
                break;
            }
            digits.append(c);
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Lowercase loader id, used in file names. */
    static String id(ModLoaderType loader) {
        return loader.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Works out which loader a version JSON belongs to by looking at the libraries it declares.
     *
     * <p>Each loader ships a library under a maven group that belongs to it, and that group is the
     * only part of an installed profile that is not the launcher's own bookkeeping: the installer
     * writes it, not us. Reading it means a profile installed by any means, including by hand or by
     * another launcher, is recognised on the same terms as one we installed.
     *
     * <p>A version JSON naming two loader groups is treated as unrecognised rather than guessed at.
     * Forge profiles list both {@code net.minecraftforge} and, in some versions, other tooling, and
     * a confident wrong answer there shows a Forge profile in a build picker that then fails to
     * install. Returning nothing lets the caller fall back to what it wrote itself.
     *
     * @return the loader, or null when the JSON is vanilla, unrecognised or ambiguous
     */
    public static ModLoaderType detectFromLibraries(JsonObject versionJson) {
        if (versionJson == null || !versionJson.has("libraries")
                || !versionJson.get("libraries").isJsonArray()) {
            return null;
        }
        ModLoaderType found = null;
        for (JsonElement element : versionJson.getAsJsonArray("libraries")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonElement name = element.getAsJsonObject().get("name");
            if (name == null || !name.isJsonPrimitive()) {
                continue;
            }
            ModLoaderType candidate = loaderForGroup(loaderGroup(name.getAsString()));
            if (candidate == null || candidate == found) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = candidate;
        }
        return found;
    }

    /** The loader that owns a maven group, or null for anything unrelated. */
    private static ModLoaderType loaderForGroup(String group) {
        if (group == null) {
            return null;
        }
        return switch (group) {
            case "net.neoforged" -> ModLoaderType.NEOFORGE;
            case "net.minecraftforge" -> ModLoaderType.FORGE;
            case "net.fabricmc" -> ModLoaderType.FABRIC;
            default -> null;
        };
    }

    /**
     * The maven group of a library coordinate.
     *
     * <p>A coordinate is {@code group:artifact:version}, optionally followed by a classifier and an
     * extension, as in {@code net.minecraftforge:forge:1.20.1-47.4.23:universal}. Only the leading
     * {@code group} matters here, and a coordinate that is not in that form belongs to no loader.
     */
    private static String loaderGroup(String coordinate) {
        int colon = coordinate.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        return coordinate.substring(0, colon).trim();
    }
}
