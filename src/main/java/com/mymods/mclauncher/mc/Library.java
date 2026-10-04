package com.mymods.mclauncher.mc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A library entry from a version JSON, plus the platform rules that decide whether it applies.
 *
 * <p>Mojang expresses platform filtering through {@code rules}: an {@code os} block with
 * {@code name} / {@code arch} / {@code version} conditions and an {@code allow}/{@code disallow}
 * verdict. Libraries without rules always apply.
 */
public record Library(String name, String path, boolean natives, Map<String, String> nativesClassifier,
                      List<Rule> rules, JsonObject downloads) {

    /**
     * @param action {@code allow} or {@code disallow}
     * @param osName {@code windows}, {@code osx}, {@code linux} or null for any
     * @param arch   {@code x86}, {@code x86_64}, {@code arm64} or null for any
     */
    public record Rule(String action, String osName, String arch) {}

    public static Library fromJson(JsonObject json) {
        String name = json.has("name") ? json.get("name").getAsString() : null;
        String mavenPath = json.has("downloads") && json.getAsJsonObject("downloads").has("artifact")
                ? relativePath(json.getAsJsonObject("downloads").getAsJsonObject("artifact"))
                : mavenCoordinates(name);

        Map<String, String> natives = new HashMap<>();
        if (json.has("natives")) {
            for (var entry : json.getAsJsonObject("natives").entrySet()) {
                natives.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        return new Library(name, mavenPath, json.has("natives"), natives, readRules(json),
                json.has("downloads") ? json.getAsJsonObject("downloads") : new JsonObject());
    }

    /**
     * Whether this entry is a platform-native jar that must be unpacked rather than put on the
     * classpath.
     *
     * <p>Two shapes exist. Up to 1.12 a library carried a {@code natives} map of platform
     * classifiers. From 1.13 on, Mojang lists each platform as its own library entry named
     * {@code group:artifact:version:natives-windows}, filtered only by rules.
     */
    public boolean isNativeArtifact() {
        if (nativesClassifier != null && !nativesClassifier.isEmpty()) {
            return true;
        }
        if (name != null && name.contains(":natives-")) {
            return true;
        }
        return path != null && path.contains("-natives-");
    }

    private static String relativePath(JsonObject artifact) {
        return artifact.has("path") ? artifact.get("path").getAsString() : null;
    }

    /** Derives the maven path from {@code group:artifact:version} when no download info exists. */
    private static String mavenCoordinates(String name) {
        if (name == null) {
            return null;
        }
        String[] parts = name.split(":");
        if (parts.length < 3) {
            return null;
        }
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2] + ".jar";
    }

    private static List<Rule> readRules(JsonObject json) {
        List<Rule> rules = new ArrayList<>();
        if (!json.has("rules")) {
            return rules;
        }
        for (JsonElement element : json.getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
            String osName = null;
            String arch = null;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name")) {
                    osName = os.get("name").getAsString();
                }
                if (os.has("arch")) {
                    arch = os.get("arch").getAsString();
                }
            }
            rules.add(new Rule(action, osName, arch));
        }
        return rules;
    }

    /** True when this library should be on the classpath for the current machine. */
    public boolean appliesHere() {
        if (rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (Rule rule : rules) {
            if (!matches(rule)) {
                continue;
            }
            if ("disallow".equals(rule.action())) {
                return false;
            }
            allowed = true;
        }
        return allowed;
    }

    private static boolean matches(Rule rule) {
        if (rule.osName() != null && !rule.osName().equals(currentOs())) {
            return false;
        }
        return rule.arch() == null || rule.arch().equals(currentArch());
    }

    public static String currentOs() {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return "windows";
        }
        return name.contains("mac") ? "osx" : "linux";
    }

    public static String currentArch() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "arm64";
            case "x86", "i386", "i686" -> "x86";
            default -> arch;
        };
    }

    /** Classifier to use for the natives jar on this platform, or null when none matches. */
    public String nativesClassifierHere() {
        if (nativesClassifier.isEmpty()) {
            return null;
        }
        String archKey = switch (currentArch()) {
            case "arm64" -> "arm64";
            case "x86" -> "x86";
            default -> "x86_64";
        };
        String classifier = nativesClassifier.get(archKey);
        return classifier == null ? nativesClassifier.get("windows") : classifier;
    }

    /** Download url for the main jar, or null when the library has no artifact info. */
    public String artifactUrl() {
        if (!downloads.has("artifact")) {
            return null;
        }
        JsonObject artifact = downloads.getAsJsonObject("artifact");
        return artifact.has("url") ? artifact.get("url").getAsString() : null;
    }

    public String artifactSha1() {
        if (!downloads.has("artifact")) {
            return null;
        }
        JsonObject artifact = downloads.getAsJsonObject("artifact");
        return artifact.has("sha1") ? artifact.get("sha1").getAsString() : null;
    }

    /** True for the pre-1.13 shape: a {@code natives} classifier map beside the plain artifact. */
    public boolean hasNativesMap() {
        return nativesClassifier != null && !nativesClassifier.isEmpty();
    }

    /** Download url for the natives jar on this platform, or null. */
    public String nativesUrl() {
        JsonObject entry = classifierDownload(nativesClassifierHere());
        return entry != null && entry.has("url") ? entry.get("url").getAsString() : null;
    }

    /** Sha1 of the natives jar on this platform, or null. */
    public String nativesSha1() {
        JsonObject entry = classifierDownload(nativesClassifierHere());
        return entry != null && entry.has("sha1") ? entry.get("sha1").getAsString() : null;
    }

    private JsonObject classifierDownload(String classifier) {
        if (classifier == null || !downloads.has("classifiers")) {
            return null;
        }
        JsonObject classifiers = downloads.getAsJsonObject("classifiers");
        return classifiers.has(classifier) ? classifiers.getAsJsonObject(classifier) : null;
    }

    /**
     * Path of the natives jar inside {@code libraries}.
     *
     * <p>For 1.13+ entries the path already points at the native jar. For the older
     * {@code natives} map the classifier is appended to the artifact name, e.g.
     * {@code lwjgl-3.3.3-natives-windows.jar} next to {@code lwjgl-3.3.3.jar}.
     */
    public String nativesPath() {
        if (path == null) {
            return null;
        }
        if (nativesClassifier == null || nativesClassifier.isEmpty()) {
            return path;
        }
        String classifier = nativesClassifierHere();
        if (classifier == null) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String base = slash < 0 ? "" : path.substring(0, slash + 1);
        String file = slash < 0 ? path : path.substring(slash + 1);
        String stem = file.endsWith(".jar") ? file.substring(0, file.length() - 4) : file;
        return base + stem + "-" + classifier + ".jar";
    }
}